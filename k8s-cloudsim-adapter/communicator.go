package main

import (
	"bytes" // Required for bytes.NewReader to send JSON payload in HTTP requests
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"sync"
)

var extenderURL string // URL of the mock scheduler extender

// --- Simplified Go Structs for our "API" ---
// These structs represent the data models for Nodes (CloudSim VMs/Containers)
// and Pods (CloudSim Cloudlets) in our simulated Kubernetes environment.

// --- In-memory Store for our simulated cluster state ---
// These maps store the current state of nodes and pods in our simulated cluster.
var (
	nodes map[int]CsNode // Stores nodes, keyed by their ID
	pods  map[int]*CsPod // Stores pods, keyed by their ID
	mu    sync.RWMutex   // Mutex to protect concurrent access to the shared maps (nodes and pods)
)

// init function is called once when the program starts.
func init() {
	nodes = make(map[int]CsNode)
	pods = make(map[int]*CsPod)
}

// handleNodes receives a list of nodes from CloudSim.
// This endpoint is used to update the control plane's view of available cluster resources.
func handleNodes(w http.ResponseWriter, r *http.Request) {
	// Only allow POST requests to this endpoint.
	if r.Method != http.MethodPost {
		http.Error(w, "Only POST method is allowed", http.StatusMethodNotAllowed)
		return
	}

	var newNodes []CsNode
	// Decode the JSON request body into the newNodes slice.
	if err := json.NewDecoder(r.Body).Decode(&newNodes); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	// Acquire a write lock to safely modify the shared 'nodes' map.
	mu.Lock()
	defer mu.Unlock()

	// Clear existing nodes and add new ones to ensure an up-to-date snapshot.
	// This is important because CloudSim might send the full list repeatedly.
	nodes = make(map[int]CsNode) // Reset the map
	for _, node := range newNodes {
		nodes[node.ID] = node // Add/update nodes
	}
	log.Printf("Received %d nodes from CloudSim. Current node count: %d\n", len(newNodes), len(nodes))

	// Send a success response.
	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, "Received %d nodes\n", len(newNodes))
}

// handlePodStatus returns the current status of a specific pod.
// This endpoint is polled by the CloudSim broker to get scheduling decisions.
func handlePodStatus(w http.ResponseWriter, r *http.Request) {
	// Only allow GET requests.
	if r.Method != http.MethodGet {
		http.Error(w, "Only GET method is allowed", http.StatusMethodNotAllowed)
		return
	}

	// Parse the URL path to extract the pod ID. Expected format: /pods/{id}/status
	pathSegments := splitPath(r.URL.Path)
	if len(pathSegments) < 3 || pathSegments[len(pathSegments)-1] != "status" || pathSegments[len(pathSegments)-3] != "pods" {
		http.Error(w, "Invalid URL path. Expected /pods/{id}/status", http.StatusBadRequest)
		return
	}

	podIDStr := pathSegments[len(pathSegments)-2] // The ID is the second to last segment
	podID, err := strconv.Atoi(podIDStr)
	if err != nil {
		http.Error(w, "Invalid CsPod ID", http.StatusBadRequest)
		return
	}

	// Acquire a read lock to safely access the shared 'pods' map.
	mu.RLock()
	defer mu.RUnlock()

	pod, exists := pods[podID]
	if !exists {
		// If the pod is not found, return 404. This might happen if it's already completed
		// or if the request is for a non-existent pod.
		http.Error(w, "CsPod not found", http.StatusNotFound)
		return
	}

	// Set Content-Type header and encode the pod status as JSON.
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(pod)
}

func handleBatchPods(w http.ResponseWriter, r *http.Request) {
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

	// Store new pods with Pending status
	mu.Lock()
	for i := range newPods {
		if newPods[i].Status == "" {
			newPods[i].Status = "Pending"
		}
		podCopy := newPods[i]
		pods[podCopy.ID] = &podCopy
	}
	mu.Unlock()

	log.Printf("Received %d pods for batch scheduling\n", len(newPods))

	// Run scheduling immediately
	schedulePendingPods(extenderURL)

	// Collect final pod statuses
	mu.RLock()
	defer mu.RUnlock()

	var finalPods []CsPod
	for _, pod := range newPods {
		if storedPod, ok := pods[pod.ID]; ok {
			finalPods = append(finalPods, *storedPod)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(finalPods)
}

// callExtenderFilter makes an HTTP POST request to the extender's filter endpoint.
// It sends the pod and available nodes, and expects a filtered list of nodes in return.
func callExtenderFilter(url string, args ExtenderArgs) ([]CsNode, error) {
	// Marshal the ExtenderArgs struct into a JSON byte slice.
	payload, err := json.Marshal(args)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal extender args: %w", err)
	}

	// Make an HTTP POST request. bytes.NewReader wraps the byte slice as an io.Reader.
	resp, err := http.Post(url, "application/json", bytes.NewReader(payload))
	if err != nil {
		return nil, fmt.Errorf("failed to call extender filter: %w", err)
	}
	defer resp.Body.Close() // Ensure the response body is closed.

	// Check for non-OK HTTP status codes.
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("extender filter returned non-OK status: %d", resp.StatusCode)
	}

	var result ExtenderFilterResult
	// Decode the JSON response body into the ExtenderFilterResult struct.
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("failed to decode extender filter result: %w", err)
	}

	// If no nodes are returned (e.g., all filtered out), return an empty slice.
	if result.Nodes == nil {
		return []CsNode{}, nil
	}
	return *result.Nodes, nil // Return the filtered nodes.
}

// callExtenderPrioritize makes an HTTP POST request to the extender's prioritize endpoint.
// It sends the pod and filtered nodes, and expects a list of host priorities in return.
func callExtenderPrioritize(url string, args ExtenderArgs) (HostPriorityList, error) {
	// Marshal the ExtenderArgs struct into a JSON byte slice.
	payload, err := json.Marshal(args)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal extender args: %w", err)
	}

	// Make an HTTP POST request.
	resp, err := http.Post(url, "application/json", bytes.NewReader(payload))
	if err != nil {
		return nil, fmt.Errorf("failed to call extender prioritize: %w", err)
	}
	defer resp.Body.Close() // Ensure the response body is closed.

	// Check for non-OK HTTP status codes.
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("extender prioritize returned non-OK status: %d", resp.StatusCode)
	}

	var result HostPriorityList
	// Decode the JSON response body into the HostPriorityList.
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("failed to decode extender prioritize result: %w", err)
	}
	return result, nil // Return the host priorities.
}

func schedulePendingPods(extenderURL string) {
	mu.RLock()
	pendingPods := []*CsPod{}
	for _, pod := range pods {
		if pod.Status == "Pending" {
			pendingPods = append(pendingPods, pod)
		}
	}
	mu.RUnlock()

	if len(pendingPods) == 0 {
		return
	}

	for _, pod := range pendingPods {
		log.Printf("Attempting to schedule CsPod %d...\n", pod.ID)

		mu.RLock()
		currentNodes := make([]CsNode, 0, len(nodes))
		for _, node := range nodes {
			currentNodes = append(currentNodes, node)
		}
		mu.RUnlock()

		if len(currentNodes) == 0 {
			log.Printf("No nodes available for CsPod %d. Marking unschedulable.\n", pod.ID)
			mu.Lock()
			pod.Status = "Unschedulable"
			mu.Unlock()
			continue
		}

		extenderArgs := ExtenderArgs{Pod: pod, Nodes: currentNodes}
		filteredNodes, err := callExtenderFilter(extenderURL+"/filter", extenderArgs)
		if err != nil {
			log.Printf("Extender filter failed for CsPod %d: %v\n", pod.ID, err)
			continue
		}
		if len(filteredNodes) == 0 {
			log.Printf("Extender filtered out all nodes for CsPod %d. Marking unschedulable.\n", pod.ID)
			mu.Lock()
			pod.Status = "Unschedulable"
			mu.Unlock()
			continue
		}

		priorities, err := callExtenderPrioritize(extenderURL+"/prioritize", ExtenderArgs{Pod: pod, Nodes: filteredNodes})
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
			mu.Lock()
			pod.Status = "Unschedulable"
			mu.Unlock()
			continue
		}

		mu.Lock()
		pod.Status = "Scheduled"
		pod.NodeName = selectedNode.Name
		pod.NodeID = selectedNode.ID
		log.Printf("CsPod %d scheduled on CsNode %s (VM ID %d)\n", pod.ID, selectedNode.Name, selectedNode.ID)
		mu.Unlock()
	}
}
