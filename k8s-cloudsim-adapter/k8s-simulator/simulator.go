package simulator

import (
	"encoding/json"
	"github.com/gorilla/mux"
	"io"
	"k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"log"
	"net/http"
)

type K8sSimulator struct {
	ExtenderURL string
	Nodes       []v1.Node
	Pods        []v1.Pod
}

func NewSimulator(url string) *K8sSimulator {
	return &K8sSimulator{
		ExtenderURL: url,
	}
}

func (sim *K8sSimulator) ServePods(w http.ResponseWriter, r *http.Request) {
	log.Printf("Attempting to return PODS!")
	podList := v1.PodList{
		TypeMeta: metav1.TypeMeta{
			Kind:       "PodList",
			APIVersion: "v1",
		},
		ListMeta: metav1.ListMeta{
			ResourceVersion: "1",
		},
		Items: sim.Pods,
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(podList)
}

// ServeNodes returns a valid Kubernetes-style NodeList
func (sim *K8sSimulator) ServeNodes(w http.ResponseWriter, r *http.Request) {
	log.Printf("Attempting to return NODES!")
	nodeList := v1.NodeList{
		TypeMeta: metav1.TypeMeta{
			Kind:       "NodeList",
			APIVersion: "v1",
		},
		ListMeta: metav1.ListMeta{
			ResourceVersion: "1",
		},
		Items: sim.Nodes,
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(nodeList)
}

// PatchPod updates the spec.nodeName of the targeted Pod
func (sim *K8sSimulator) PatchPod(w http.ResponseWriter, r *http.Request) {
	log.Printf("Attempting to PATCH!")
	vars := mux.Vars(r)
	namespace := vars["namespace"]
	name := vars["name"]

	// Read and parse the patch body
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "unable to read patch body", http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	var patch struct {
		Spec struct {
			NodeName string `json:"nodeName"`
		} `json:"spec"`
	}

	if err := json.Unmarshal(body, &patch); err != nil {
		http.Error(w, "invalid patch format", http.StatusBadRequest)
		return
	}

	// Find the Pod and update it
	for i := range sim.Pods {
		pod := &sim.Pods[i]
		if pod.ObjectMeta.Name == name && pod.ObjectMeta.Namespace == namespace {
			pod.Spec.NodeName = patch.Spec.NodeName

			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			json.NewEncoder(w).Encode(pod)
			return
		}
	}

	http.Error(w, "pod not found", http.StatusNotFound)
}
