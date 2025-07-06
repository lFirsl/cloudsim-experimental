package kube_client

import (
	"fmt"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
	"k8s.io/client-go/util/homedir"
	"log"
	"path/filepath"
	"time"
)

type KubeClient struct {
	clientset *kubernetes.Clientset
}

func NewKubeClient(kubeconfigPath string) *KubeClient {
	if kubeconfigPath == "" {
		kubeconfigPath = filepath.Join(homedir.HomeDir(), ".kube", "config")
	}

	config, err := clientcmd.BuildConfigFromFlags("", kubeconfigPath)
	if err != nil {
		panic(err)
	}

	clientset, err := kubernetes.NewForConfig(config)
	if err != nil {
		panic(err)
	}

	return &KubeClient{clientset: clientset}
}

func (kc *KubeClient) ResetCluster() error {
	err := kc.DeleteAllPods()
	if err != nil {
		return err
	}
	err = kc.DeleteAllNodes()
	if err != nil {
		return err
	}
	return nil
}

func (kc *KubeClient) DeletePodAndWaitForRescheduling(cloudletID int) ([]*corev1.Pod, error) {
	podName := fmt.Sprintf("cspod-%d", cloudletID)
	log.Printf("Deleting pod %s (cloudlet ID %d) and watching for rescheduling...", podName, cloudletID)

	// Capture pre-deletion pod statuses
	prevPods, err := kc.GetPods("default")
	if err != nil {
		return nil, fmt.Errorf("failed to fetch pods before deletion: %w", err)
	}

	// Delete pod
	if err := kc.DeletePod(podName); err != nil {
		return nil, fmt.Errorf("failed to delete pod: %w", err)
	}

	// Check if all pods are already scheduled (no need to wait)
	allScheduled := true
	for _, pod := range prevPods {
		if pod.Spec.NodeName == "" {
			allScheduled = false
			break
		}
	}
	if allScheduled {
		log.Println("All pods were already scheduled before deletion. Skipping wait.")
		return []*corev1.Pod{}, nil
	}

	prevStatuses := make(map[string]string)
	for _, pod := range prevPods {
		prevStatuses[pod.Name] = string(pod.Status.Phase)
	}

	// Watch for rescheduling
	const maxAttempts = 20
	const delay = time.Second / 4

	for i := 0; i < maxAttempts; i++ {
		log.Printf("Attempt %d to detect rescheduling...", i)
		currPods, err := kc.GetPods("default")
		if err != nil {
			return nil, fmt.Errorf("failed to fetch pods after deletion: %w", err)
		}

		var newlyScheduled []*corev1.Pod
		for _, pod := range currPods {
			prevStatus := prevStatuses[pod.Name]
			currStatus := string(pod.Status.Phase)
			if pod.Spec.NodeName != "" && prevStatus != currStatus {
				newlyScheduled = append(newlyScheduled, pod)
			}
		}

		if len(newlyScheduled) > 0 {
			return newlyScheduled, nil
		}

		time.Sleep(delay)
	}

	return []*corev1.Pod{}, nil // No rescheduling detected within timeout
}
