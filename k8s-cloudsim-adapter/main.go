package main

import (
	"github.com/gorilla/mux"
	"k8s-cloudsim-adapter/communicator"
	simulator "k8s-cloudsim-adapter/k8s-simulator"
	"log"
	"net/http"
)

func main() {
	// Config
	port := ":8080"
	extenderURL := "http://localhost:8081"

	log.Printf("Starting K8s Sim Control Plane POC on port %s\n", port)
	log.Printf("Configured to call scheduler extender at %s\n", extenderURL)

	// Initialize simulator and communicator
	sim := simulator.NewSimulator(extenderURL)
	comm := communicator.NewCommunicator(extenderURL)

	// Add test nodes
	sim.AddTestNode("node-1", "4", "8Gi")
	sim.AddTestNode("node-2", "2", "4Gi")

	// Add test pods
	sim.AddTestPod("nginx-pod", "default", "nginx:latest")
	sim.AddTestPod("busybox-pod", "default", "busybox")

	// Set up router
	router := mux.NewRouter()

	// --- Kubernetes-compatible API endpoints (for the scheduler) ---
	router.HandleFunc("/api/v1/pods", sim.ServePods).Methods("GET")
	router.HandleFunc("/api/v1/nodes", sim.ServeNodes).Methods("GET")
	router.HandleFunc("/api/v1/namespaces/{namespace}/pods/{name}/status", sim.PatchPod).Methods("PATCH")

	// --- Internal simulation/control endpoints ---
	router.HandleFunc("/nodes", comm.HandleNodes).Methods("POST")
	// router.HandleFunc("/schedule-pods", comm.HandleBatchPods).Methods("POST") // ✅ External batch pod submitter (WIP)
	router.HandleFunc("/pods/", comm.HandlePodStatus).Methods("POST")

	// Start server
	log.Printf("Serving HTTP API on %s\n", port)
	log.Fatal(http.ListenAndServe("0.0.0.0:8080", router)) // ✅ GOOD: this listens on all IPs
}
