package kube_client

import (
	"encoding/json"
	"net/http"
)

func MakeCreatePodHandler(kc *KubeClient) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Get pod name from query param
		podName := r.URL.Query().Get("name")
		if podName == "" {
			http.Error(w, "Missing 'name' query parameter", http.StatusBadRequest)
			return
		}

		// Call your real function
		err := kc.CreateFakePod(podName)
		if err != nil {
			http.Error(w, "Failed to create pod: "+err.Error(), http.StatusInternalServerError)
			return
		}

		json.NewEncoder(w).Encode(map[string]string{
			"status": "created",
			"pod":    podName,
		})
	}
}

func MakeDeletePodHandler(kc *KubeClient) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		podName := r.URL.Query().Get("name")
		if podName == "" {
			http.Error(w, "Missing 'name' query parameter", http.StatusBadRequest)
			return
		}

		err := kc.DeletePod(podName)
		if err != nil {
			http.Error(w, "Failed to delete pod: "+err.Error(), http.StatusInternalServerError)
			return
		}

		json.NewEncoder(w).Encode(map[string]string{
			"status": "deleted",
			"pod":    podName,
		})
	}
}

func MakeCreateNodeHandler(kc *KubeClient) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		nodeName := r.URL.Query().Get("name")
		if nodeName == "" {
			http.Error(w, "Missing 'name' query parameter", http.StatusBadRequest)
			return
		}

		err := kc.CreateFakeNode(nodeName)
		if err != nil {
			http.Error(w, "Failed to create node: "+err.Error(), http.StatusInternalServerError)
			return
		}

		json.NewEncoder(w).Encode(map[string]string{
			"status": "created",
			"node":   nodeName,
		})
	}
}

func MakeDeleteNodeHandler(kc *KubeClient) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		nodeName := r.URL.Query().Get("name")
		if nodeName == "" {
			http.Error(w, "Missing 'name' query parameter", http.StatusBadRequest)
			return
		}

		err := kc.DeleteNode(nodeName)
		if err != nil {
			http.Error(w, "Failed to delete node: "+err.Error(), http.StatusInternalServerError)
			return
		}

		json.NewEncoder(w).Encode(map[string]string{
			"status": "deleted",
			"node":   nodeName,
		})
	}
}
