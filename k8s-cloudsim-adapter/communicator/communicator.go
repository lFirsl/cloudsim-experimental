package communicator

import (
	"bytes"
	"encoding/json"
	"fmt"
	"k8s-cloudsim-adapter/utils"
	"log"
	"net/http"
	"strconv"
	"sync"
)

type Communicator struct {
	extenderURL string
	mu          sync.RWMutex
	nodes       map[int]CsNode
	pods        map[int]*CsPod
}

func NewCommunicator(url string) *Communicator {
	return &Communicator{
		extenderURL: url,
		nodes:       make(map[int]CsNode),
		pods:        make(map[int]*CsPod),
	}
}

func (c *Communicator) HandleNodes(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Only POST method is allowed", http.StatusMethodNotAllowed)
		return
	}

	var newNodes []CsNode
	if err := json.NewDecoder(r.Body).Decode(&newNodes); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	c.nodes = make(map[int]CsNode)
	for _, node := range newNodes {
		c.nodes[node.ID] = node
	}
	log.Printf("Received %d nodes from CloudSim. Current node count: %d\n", len(newNodes), len(c.nodes))
	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, "Received %d nodes\n", len(newNodes))
}

func (c *Communicator) HandlePodStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Only GET method is allowed", http.StatusMethodNotAllowed)
		return
	}

	pathSegments := utils.SplitPath(r.URL.Path)
	if len(pathSegments) < 3 || pathSegments[len(pathSegments)-1] != "status" || pathSegments[len(pathSegments)-3] != "pods" {
		http.Error(w, "Invalid URL path. Expected /pods/{id}/status", http.StatusBadRequest)
		return
	}

	podIDStr := pathSegments[len(pathSegments)-2]
	podID, err := strconv.Atoi(podIDStr)
	if err != nil {
		http.Error(w, "Invalid CsPod ID", http.StatusBadRequest)
		return
	}

	c.mu.RLock()
	defer c.mu.RUnlock()
	pod, exists := c.pods[podID]
	if !exists {
		http.Error(w, "CsPod not found", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(pod)
}

func (c *Communicator) HandleBatchPods(w http.ResponseWriter, r *http.Request) {
	log.Printf("Starting handleBatchPods()")
	if r.Method != http.MethodPost {
		http.Error(w, "Only POST method is allowed", http.StatusMethodNotAllowed)
		return
	}

	var newPods []CsPod
	if err := json.NewDecoder(r.Body).Decode(&newPods); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	c.mu.Lock()
	for i := range newPods {
		if newPods[i].Status == "" {
			newPods[i].Status = "Pending"
		}
		podCopy := newPods[i]
		c.pods[podCopy.ID] = &podCopy
	}
	c.mu.Unlock()

	log.Printf("Received %d pods for batch scheduling\n", len(newPods))
	c.SchedulePendingPods()

	c.mu.RLock()
	defer c.mu.RUnlock()
	var finalPods []CsPod
	for _, pod := range newPods {
		if storedPod, ok := c.pods[pod.ID]; ok {
			finalPods = append(finalPods, *storedPod)
		}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(finalPods)
}

func (c *Communicator) SchedulePendingPods() {
	c.mu.RLock()
	pendingPods := []*CsPod{}
	for _, pod := range c.pods {
		if pod.Status == "Pending" {
			pendingPods = append(pendingPods, pod)
		}
	}
	c.mu.RUnlock()
	if len(pendingPods) == 0 {
		return
	}

	for _, pod := range pendingPods {
		log.Printf("Attempting to schedule CsPod %d...\n", pod.ID)
		c.mu.RLock()
		currentNodes := make([]CsNode, 0, len(c.nodes))
		for _, node := range c.nodes {
			currentNodes = append(currentNodes, node)
		}
		c.mu.RUnlock()
		if len(currentNodes) == 0 {
			log.Printf("No nodes available for CsPod %d. Marking unschedulable.\n", pod.ID)
			c.mu.Lock()
			pod.Status = "Unschedulable"
			c.mu.Unlock()
			continue
		}

		extenderArgs := ExtenderArgs{Pod: pod, Nodes: currentNodes}
		filteredNodes, err := callExtenderFilter(c.extenderURL+"/filter", extenderArgs)
		if err != nil {
			log.Printf("Extender filter failed for CsPod %d: %v\n", pod.ID, err)
			continue
		}
		if len(filteredNodes) == 0 {
			log.Printf("Extender filtered out all nodes for CsPod %d. Marking unschedulable.\n", pod.ID)
			c.mu.Lock()
			pod.Status = "Unschedulable"
			c.mu.Unlock()
			continue
		}

		priorities, err := callExtenderPrioritize(c.extenderURL+"/prioritize", ExtenderArgs{Pod: pod, Nodes: filteredNodes})
		if err != nil {
			log.Printf("Extender prioritize failed for CsPod %d: %v\n", pod.ID, err)
			continue
		}

		var selectedNode CsNode
		maxScore := int64(-1)
		found := false
		for _, p := range priorities {
			for _, node := range filteredNodes {
				if node.Name == p.Host {
					if p.Score > maxScore {
						maxScore = p.Score
						selectedNode = node
						found = true
					}
					break
				}
			}
		}

		if !found {
			log.Printf("No suitable node found for CsPod %d. Marking unschedulable.\n", pod.ID)
			c.mu.Lock()
			pod.Status = "Unschedulable"
			c.mu.Unlock()
			continue
		}

		c.mu.Lock()
		pod.Status = "Scheduled"
		pod.NodeName = selectedNode.Name
		pod.NodeID = selectedNode.ID
		log.Printf("CsPod %d scheduled on CsNode %s (VM ID %d)\n", pod.ID, selectedNode.Name, selectedNode.ID)
		c.mu.Unlock()
	}
}

func callExtenderFilter(url string, args ExtenderArgs) ([]CsNode, error) {
	payload, err := json.Marshal(args)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal extender args: %w", err)
	}
	resp, err := http.Post(url, "application/json", bytes.NewReader(payload))
	if err != nil {
		return nil, fmt.Errorf("failed to call extender filter: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("extender filter returned non-OK status: %d", resp.StatusCode)
	}
	var result ExtenderFilterResult
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("failed to decode extender filter result: %w", err)
	}
	if result.Nodes == nil {
		return []CsNode{}, nil
	}
	return *result.Nodes, nil
}

func callExtenderPrioritize(url string, args ExtenderArgs) (HostPriorityList, error) {
	payload, err := json.Marshal(args)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal extender args: %w", err)
	}
	resp, err := http.Post(url, "application/json", bytes.NewReader(payload))
	if err != nil {
		return nil, fmt.Errorf("failed to call extender prioritize: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("extender prioritize returned non-OK status: %d", resp.StatusCode)
	}
	var result HostPriorityList
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("failed to decode extender prioritize result: %w", err)
	}
	return result, nil
}
