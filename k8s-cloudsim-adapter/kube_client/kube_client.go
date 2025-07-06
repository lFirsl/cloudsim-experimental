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

func (kc *KubeClient) PodDeleteUpdate(podName, namespace string) ([]*corev1.Pod, error) {
	// Step 1: Snapshot before delete
	prevPods, err := kc.GetPods(namespace)
	if err != nil {
		return nil, err
	}

	// Step 2: Save mapping of pod name → assigned node (empty = unscheduled)
	prevAssignments := map[string]string{}
	for _, pod := range prevPods {
		prevAssignments[pod.Name] = pod.Spec.NodeName
	}

	// Step 3: Delete pod
	if err := kc.DeletePod(podName); err != nil {
		return nil, err
	}

	// Step 4: Wait & detect changes
	const maxAttempts = 30
	const delay = time.Second

	for i := 0; i < maxAttempts; i++ {
		time.Sleep(delay)

		currPods, err := kc.GetPods(namespace)
		if err != nil {
			return nil, err
		}

		// Check if any pod changed its assigned node
		for _, pod := range currPods {
			prevNode := prevAssignments[pod.Name]
			if pod.Spec.NodeName != prevNode {
				log.Printf("Pod %s was rescheduled: %q -> %q", pod.Name, prevNode, pod.Spec.NodeName)
				return currPods, nil
			}
		}
	}

	// No changes detected within time window
	return nil, fmt.Errorf("no pod rescheduling detected after deletion of %s", podName)
}
