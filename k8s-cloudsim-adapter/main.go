package main

import (
	"log"
	"net/http"
)

// main function is the entry point of the Go control plane application.
func main() {
	port := ":8080"                        // Port on which the control plane HTTP server will listen
	extenderURL := "http://localhost:8081" // URL of the mock scheduler extender

	log.Printf("Starting K8s Sim Control Plane POC on port %s\n", port)
	log.Printf("Configured to call scheduler extender at %s\n", extenderURL)

	// Register HTTP handlers for different API endpoints.
	comm := NewCommunicator("http://localhost:8081")
	http.HandleFunc("/nodes", comm.HandleNodes)
	http.HandleFunc("/schedule-pods", comm.HandleBatchPods)
	http.HandleFunc("/pods/", comm.HandlePodStatus)

	// Start the HTTP server and log any fatal errors (e.g., port already in use).
	log.Fatal(http.ListenAndServe(port, nil))
}
