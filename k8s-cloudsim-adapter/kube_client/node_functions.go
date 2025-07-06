package kube_client

import (
	"context"
	"fmt"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/utils/pointer"
	"log"
)

func (kc *KubeClient) GetNodes() ([]*corev1.Node, error) {
	nodeList, err := kc.clientset.CoreV1().Nodes().List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		return nil, err
	}

	nodes := make([]*corev1.Node, 0, len(nodeList.Items))
	for i := range nodeList.Items {
		nodes = append(nodes, &nodeList.Items[i])
	}
	return nodes, nil
}

func (kc *KubeClient) AreAllNodesReady() (bool, error) {
	nodes, err := kc.GetNodes()
	if err != nil {
		return false, err
	}

	for _, node := range nodes {
		ready := false
		for _, condition := range node.Status.Conditions {
			if condition.Type == corev1.NodeReady && condition.Status == corev1.ConditionTrue {
				ready = true
				break
			}
		}
		if !ready {
			log.Println("Nodes ready check: false")
			return false, nil
		}
	}
	log.Println("Nodes ready check: TRUE")
	return true, nil
}

func (kc *KubeClient) CreateFakeNode(name string) error {
	node := &corev1.Node{
		ObjectMeta: metav1.ObjectMeta{
			Name: name,
			Labels: map[string]string{
				"beta.kubernetes.io/arch":       "amd64",
				"beta.kubernetes.io/os":         "linux",
				"kubernetes.io/arch":            "amd64",
				"kubernetes.io/hostname":        name,
				"kubernetes.io/os":              "linux",
				"kubernetes.io/role":            "agent",
				"node-role.kubernetes.io/agent": "",
				"type":                          "kwok",
			},
			Annotations: map[string]string{
				"kwok.x-k8s.io/node":           "fake",
				"node.alpha.kubernetes.io/ttl": "0",
			},
		},
		Spec: corev1.NodeSpec{
			Taints: []corev1.Taint{
				{
					Key:    "kwok.x-k8s.io/node",
					Value:  "fake",
					Effect: corev1.TaintEffectNoSchedule,
				},
			},
		},
		Status: corev1.NodeStatus{
			Phase: corev1.NodeRunning,
			Allocatable: corev1.ResourceList{
				corev1.ResourceCPU:                    resource.MustParse("32"),
				corev1.ResourceMemory:                 resource.MustParse("256Gi"),
				corev1.ResourcePods:                   resource.MustParse("110"),
				corev1.ResourceName("nvidia.com/gpu"): resource.MustParse("32"),
			},
			Capacity: corev1.ResourceList{
				corev1.ResourceCPU:                    resource.MustParse("32"),
				corev1.ResourceMemory:                 resource.MustParse("256Gi"),
				corev1.ResourcePods:                   resource.MustParse("110"),
				corev1.ResourceName("nvidia.com/gpu"): resource.MustParse("32"),
			},
			NodeInfo: corev1.NodeSystemInfo{
				Architecture:            "amd64",
				OperatingSystem:         "linux",
				KernelVersion:           "",
				KubeletVersion:          "fake",
				KubeProxyVersion:        "fake",
				MachineID:               "",
				BootID:                  "",
				OSImage:                 "",
				SystemUUID:              "",
				ContainerRuntimeVersion: "",
			},
		},
	}
	err := kc.SendNode(node)
	if err != nil {
		return err
	}
	return nil
}

func (kc *KubeClient) SendNode(node *corev1.Node) error {
	_, err := kc.clientset.CoreV1().
		Nodes().
		Create(context.TODO(), node, metav1.CreateOptions{})
	return err
}

func (kc *KubeClient) DeleteNode(name string) error {
	return kc.clientset.CoreV1().
		Nodes().
		Delete(context.TODO(), name, metav1.DeleteOptions{
			GracePeriodSeconds: pointer.Int64(0),
		})
}

func (kc *KubeClient) DeleteNodes(nodes []*corev1.Node) error {
	for _, node := range nodes {
		if err := kc.DeleteNode(node.Name); err != nil {
			return fmt.Errorf("failed to delete node %s: %w", node.Name, err)
		}
	}
	return nil
}

func (kc *KubeClient) DeleteAllNodes() error {
	return kc.clientset.CoreV1().
		Nodes().
		DeleteCollection(context.TODO(), metav1.DeleteOptions{}, metav1.ListOptions{})
}

func (kc *KubeClient) DeleteKwokNodes() error {
	return kc.clientset.CoreV1().
		Nodes().
		DeleteCollection(
			context.TODO(),
			metav1.DeleteOptions{},
			metav1.ListOptions{
				LabelSelector: "type=kwok",
			},
		)
}
