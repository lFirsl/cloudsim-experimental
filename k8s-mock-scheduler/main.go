package main

import (
	"encoding/json"
	"log"
	"net/http"
)

// These structs must match the ExtenderArgs, ExtenderFilterResult, HostPriorityList
// from the k8s-sim-control-plane.
type ExtenderArgs struct {
	Pod   interface{} `json:"pod"` // Use interface{} as we don't strictly need to parse the Pod here
	Nodes []Node      `json:"nodes"`
}

type Node struct {
	ID       int    `json:"id"`
	Name     string `json:"name"`
	MIPSAval int    `json:"mipsAvailable"`
	RAMAval  int    `json:"ramAvailable"`
}

type ExtenderFilterResult struct {
	Nodes *[]Node `json:"nodes,omitempty"`
}

type HostPriority struct {
	Host  string `json:"host"`
	Score int64  `json:"score"`
}

type HostPriorityList []HostPriority

func handleFilter(w http.ResponseWriter, r *http.Request) {
	log.Println("Received filter request")
	var args ExtenderArgs
	if err := json.NewDecoder(r.Body).Decode(&args); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	// POC Logic: No actual filtering, return all nodes
	result := ExtenderFilterResult{Nodes: &args.Nodes}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
	log.Printf("Filtered %d nodes (returned all %d)\n", len(args.Nodes), len(args.Nodes))
}

func handlePrioritize(w http.ResponseWriter, r *http.Request) {
	log.Println("Received prioritize request")
	var args ExtenderArgs
	if err := json.NewDecoder(r.Body).Decode(&args); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	// POC Logic: Assign a constant score to all nodes (e.g., 100)
	var priorities HostPriorityList
	for _, node := range args.Nodes {
		priorities = append(priorities, HostPriority{Host: node.Name, Score: 100})
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(priorities)
	log.Printf("Prioritized %d nodes\n", len(priorities))
}

func main() {
	port := ":8081"
	log.Printf("Starting Mock Scheduler Extender on port %s\n", port)

	http.HandleFunc("/filter", handleFilter)
	http.HandleFunc("/prioritize", handlePrioritize)

	log.Fatal(http.ListenAndServe(port, nil))
}
