package main

import (
	"flag"
	"fmt"
	"github.com/gorilla/mux"
	"k8s-cloudsim-adapter/communicator"
	"k8s-cloudsim-adapter/kube_client"
	"k8s.io/client-go/util/homedir"
	"log"
	"net/http"
	"path/filepath"
)

func main() {
	// Config
	port := ":8080"
	extenderURL := "http://localhost:8081"

	log.Printf("Starting K8s Sim Control Plane POC on port %s\n", port)
	log.Printf("Configured to call scheduler extender at %s\n", extenderURL)

	//==========K8S=SETUP========
	var kubeconfig *string
	if home := homedir.HomeDir(); home != "" {
		kubeconfig = flag.String("kubeconfig", filepath.Join(home, ".kube", "config"), "(optional) absolute path to the kubeconfig file")
	} else {
		kubeconfig = flag.String("kubeconfig", "", "absolute path to the kubeconfig file")
	}
	flag.Parse()
	kc := kube_client.NewKubeClient(*kubeconfig)

	pods, err := kc.GetPods("default")
	if err != nil {
		fmt.Printf("❌ Failed to list pods: %v\n", err)
	} else {
		fmt.Printf("✅ Found %d pods:\n", len(pods))
		for _, pod := range pods {
			fmt.Printf(" - %s (%s)\n", pod.Name, pod.Status.Phase)
		}
	}
	// Initialize simulator and communicator
	comm := communicator.NewCommunicator(extenderURL, kc)

	// Set up router
	router := mux.NewRouter()

	// --- Internal simulation/control endpoints ---
	router.HandleFunc("/nodes", comm.HandleNodes).Methods("POST")
	router.HandleFunc("/schedule-pods", comm.HandleBatchPods).Methods("POST")
	router.HandleFunc("/pods/", comm.HandlePodStatus).Methods("POST")
	router.HandleFunc("/pods/update-state", comm.HandleDeleteCloudletAndWait).Methods("POST")

	router.HandleFunc("/pods/create", kube_client.MakeCreatePodHandler(kc))
	router.HandleFunc("/pods/delete", kube_client.MakeDeletePodHandler(kc))
	router.HandleFunc("/nodes/create", kube_client.MakeCreateNodeHandler(kc))
	router.HandleFunc("/nodes/delete", kube_client.MakeDeleteNodeHandler(kc))
	router.HandleFunc("/pods/delete-all", kc.HandleDeleteAllPods)
	router.HandleFunc("/nodes/delete-all", kc.HandleDeleteAllNodes)
	router.HandleFunc("/reset", kc.HandleResetCluster)

	// Start server
	log.Printf("Serving HTTP API on %s\n", port)

	log.Fatal(http.ListenAndServe("0.0.0.0:8080", router))

}
