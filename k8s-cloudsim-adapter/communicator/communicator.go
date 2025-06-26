package communicator

import (
	"bytes"
	"encoding/json"
	"fmt"
	"k8s-cloudsim-adapter/kube_client"
	"k8s-cloudsim-adapter/utils"
	"log"
	"net/http"
	"strconv"
	"sync"
	"time"
)

type Communicator struct {
	extenderURL string
	mu          sync.RWMutex
	nodes       map[int]CsNode
	pods        map[int]*CsPod
	kubeClient  *kube_client.KubeClient // <--- ADD THIS
}

func NewCommunicator(url string, kc *kube_client.KubeClient) *Communicator {
	return &Communicator{
		extenderURL: url,
		nodes:       make(map[int]CsNode),
		pods:        make(map[int]*CsPod),
		kubeClient:  kc,
	}
}

// NOTE: This is currently a full replace. You'll need to change it if you want a merge.
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

	// Step 1: Update internal map
	c.mu.Lock()
	c.nodes = make(map[int]CsNode)
	for _, node := range newNodes {
		c.nodes[node.ID] = node
	}
	c.mu.Unlock()

	log.Printf("Received %d nodes from CloudSim. Current node count: %d\n", len(newNodes), len(c.nodes))

	// Step 2: Send them to Kubernetes
	if err := c.SendFakeNodesFromCs(newNodes); err != nil {
		http.Error(w, "Failed to send nodes to Kubernetes: "+err.Error(), http.StatusInternalServerError)
		return
	}

	// Wait for all nodes to be ready
	const maxAttempts = 20
	const delay = time.Second

	ready := false
	for i := 0; i < maxAttempts; i++ {
		ok, err := c.kubeClient.AreAllNodesReady()
		if err != nil {
			http.Error(w, "Error checking node readiness: "+err.Error(), http.StatusInternalServerError)
			return
		}
		if ok {
			ready = true
			break
		}
		time.Sleep(delay)
	}

	if !ready {
		http.Error(w, "Timeout: Not all nodes became ready in time", http.StatusRequestTimeout)
		return
	}

	// Step 3: Respond success
	w.WriteHeader(http.StatusOK)
	log.Println(w, "Received and sent %d nodes to Kubernetes\n", len(newNodes))
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
	log.Printf("Starting HandleBatchPods()")

	if r.Method != http.MethodPost {
		http.Error(w, "Only POST method is allowed", http.StatusMethodNotAllowed)
		return
	}

	// Step 1: Decode input
	var newPods []CsPod
	if err := json.NewDecoder(r.Body).Decode(&newPods); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	// Step 2: Track them in communicator memory
	c.mu.Lock()
	for i := range newPods {
		if newPods[i].Status == "" {
			newPods[i].Status = "Pending"
		}
		podCopy := newPods[i]
		c.pods[podCopy.ID] = &podCopy
	}
	c.mu.Unlock()

	// Step 3: Send to Kubernetes
	if err := c.SendFakePodsFromCs(newPods); err != nil {
		http.Error(w, "Failed to send pods to Kubernetes: "+err.Error(), http.StatusInternalServerError)
		return
	}
	log.Printf("Sent %d fake pods to Kubernetes", len(newPods))

	// Step 4: Wait until they are all scheduled
	const maxAttempts = 30
	const delay = time.Second
	scheduled := false

	for i := 0; i < maxAttempts; i++ {
		ok, err := c.kubeClient.AreAllPodsScheduled("")
		if err != nil {
			http.Error(w, "Error checking pod scheduling: "+err.Error(), http.StatusInternalServerError)
			return
		}
		if ok {
			scheduled = true
			break
		}
		time.Sleep(delay)
	}

	if !scheduled {
		http.Error(w, "Timeout: Not all pods were scheduled in time", http.StatusRequestTimeout)
		return
	}

	// Step 5: Fetch from Kubernetes
	k8sPods, err := c.kubeClient.GetPods("default")
	if err != nil {
		http.Error(w, "Failed to fetch pods from Kubernetes: "+err.Error(), http.StatusInternalServerError)
		return
	}

	// Step 6: Convert to []CsPod
	csPods := ConvertToCsPods(k8sPods)

	log.Printf("Pods scheduling success - returning response")
	for podID, pod := range csPods {
		log.Println("Pod", podID, "assigned to node", pod.NodeID)
	}

	// Step 7: Return result
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(csPods); err != nil {
		http.Error(w, "Failed to encode response: "+err.Error(), http.StatusInternalServerError)
		return
	}
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

func (c *Communicator) getNodesSnapshot() []CsNode {
	c.mu.RLock()
	defer c.mu.RUnlock()
	nodes := make([]CsNode, 0, len(c.nodes))
	for _, n := range c.nodes {
		nodes = append(nodes, n)
	}
	return nodes
}
