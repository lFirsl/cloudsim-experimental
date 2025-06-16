package main

import (
	"log"
	"net/http"
)

// main function is the entry point of the Go control plane application.
func main() {
	port := ":8080"                       // Port on which the control plane HTTP server will listen
	extenderURL = "http://localhost:8081" // URL of the mock scheduler extender

	log.Printf("Starting K8s Sim Control Plane POC on port %s\n", port)
	log.Printf("Configured to call scheduler extender at %s\n", extenderURL)

	// Register HTTP handlers for different API endpoints.
	http.HandleFunc("/schedule-pods", handleBatchPods)
	http.HandleFunc("/nodes", handleNodes)
	http.HandleFunc("/pods/", handlePodStatus) // Handles requests like /pods/{id}/status

	// Start the HTTP server and log any fatal errors (e.g., port already in use).
	log.Fatal(http.ListenAndServe(port, nil))
}
