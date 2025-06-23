package kube_client

import (
	"context"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
	"k8s.io/client-go/util/homedir"
	"path/filepath"
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

func (kc *KubeClient) GetPods(namespace string) ([]string, error) {
	pods, err := kc.clientset.CoreV1().Pods(namespace).List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		return nil, err
	}

	var names []string
	for _, pod := range pods.Items {
		names = append(names, pod.Name)
	}
	return names, nil
}
