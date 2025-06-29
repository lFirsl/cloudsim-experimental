package communicator

// CsNode represents a simulated Kubernetes CsNode (which maps to a CloudSim VM or Container).
type CsNode struct {
	ID       int    `json:"id"`            // Unique identifier for the node (CloudSim VM/Container ID)
	Name     string `json:"name"`          // Name of the node (e.g., "vm-0", "container-1")
	MIPSAval int    `json:"mipsAvailable"` // Available MIPS on this node
	RAMAval  int    `json:"ramAvailable"`  // Available RAM on this node (in MB)

	Pes  int    `json:"pes"`  // Number of processing elements
	BW   int64  `json:"bw"`   // Bandwidth
	Size int64  `json:"size"` // Storage size
	Type string `json:"type"` // "vm" or "container"
}

// CsPod represents a simulated Kubernetes CsPod (which maps to a CloudSim Cloudlet).
type CsPod struct {
	ID             int     `json:"id"`
	Name           string  `json:"name"`
	Length         int64   `json:"length"`         // cloudletLength
	Pes            int     `json:"pes"`            // Number of processing elements (cores)
	FileSize       int64   `json:"fileSize"`       // Input size
	OutputSize     int64   `json:"outputSize"`     // Output size
	UtilizationCPU float64 `json:"utilizationCpu"` // 0.0 to 1.0
	UtilizationRAM float64 `json:"utilizationRam"`
	UtilizationBW  float64 `json:"utilizationBw"`

	Status        string `json:"status"` // Kubernetes status ("Pending", "Running", etc.)
	NodeName      string `json:"nodeName,omitempty"`
	NodeID        int    `json:"vmId"` // CloudSim VM id
	SchedulerName string `json:"schedulerName,omitempty"`
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
