package communicator

// CsNode represents a simulated Kubernetes CsNode (which maps to a CloudSim VM or Container).
type CsNode struct {
	ID       int    `json:"id"`            // Unique identifier for the node (CloudSim VM/Container ID)
	Name     string `json:"name"`          // Name of the node (e.g., "vm-0", "container-1")
	MIPSAval int    `json:"mipsAvailable"` // Available MIPS on this node
	RAMAval  int    `json:"ramAvailable"`  // Available RAM on this node (in MB)
}

// CsPod represents a simulated Kubernetes CsPod (which maps to a CloudSim Cloudlet).
type CsPod struct {
	ID       int    `json:"id"`                 // Unique identifier for the pod (CloudSim Cloudlet ID)
	Name     string `json:"name"`               // Name of the pod (e.g., "cloudlet-0")
	MIPSReq  int    `json:"mipsRequested"`      // MIPS requested by this pod
	RAMReq   int    `json:"ramRequested"`       // RAM requested by this pod (in MB)
	Status   string `json:"status"`             // Current status of the pod ("Pending", "Scheduled", "Unschedulable")
	NodeName string `json:"nodeName,omitempty"` // The name of the node it's scheduled on (empty if not scheduled)
	NodeID   int    `json:"vmId"`               // The CloudSim VM/Container ID it's scheduled on.
	// `omitempty` removed to ensure vmId is always present in JSON, even if 0.
	SchedulerName string `json:"schedulerName,omitempty"` // Example: if you use a specific scheduler name for this pod
}

// --- Simplified Kubernetes Extender API Structs ---
// These structs mimic the actual Kubernetes Extender API payloads,
// used for communication between the control plane and the mock scheduler extender.

// ExtenderArgs is the payload sent to the extender's filter/prioritize endpoints.
type ExtenderArgs struct {
	Pod   *CsPod   `json:"pod"`   // The pod to be scheduled
	Nodes []CsNode `json:"nodes"` // The list of available nodes for scheduling
}

// ExtenderFilterResult is the payload received from the extender's filter endpoint.
type ExtenderFilterResult struct {
	Nodes *[]CsNode `json:"nodes,omitempty"` // Filtered list of nodes that are viable for the pod
	// Additional fields like FailedNodes, Error could be added for more detailed responses
}

// HostPriority is an item in HostPriorityList, indicating a node's score.
type HostPriority struct {
	Host  string `json:"host"`  // The name of the host (node)
	Score int64  `json:"score"` // The scheduling score for this host
}

// HostPriorityList is the payload received from the extender's prioritize endpoint.
type HostPriorityList []HostPriority
