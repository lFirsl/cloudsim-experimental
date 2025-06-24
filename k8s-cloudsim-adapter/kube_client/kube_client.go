package kube_client

import (
	"context"
	"fmt"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
	"k8s.io/client-go/util/homedir"
	"log"
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

func (kc *KubeClient) GetPods(namespace string) ([]*corev1.Pod, error) {
	if namespace == "" {
		namespace = "default"
	}

	list, err := kc.clientset.CoreV1().Pods(namespace).List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		return nil, err
	}

	var pods []*corev1.Pod
	for i := range list.Items {
		pods = append(pods, &list.Items[i])
	}
	return pods, nil
}

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

func (kc *KubeClient) AreAllPodsScheduled(namespace string) (bool, error) {
	pods, err := kc.GetPods(namespace)
	if err != nil {
		return false, err
	}

	for _, pod := range pods {
		if pod.Spec.NodeName == "" {
			log.Println("Pods scheduled check: false")
			return false, nil
		}
	}
	log.Println("Pods scheduled check: TRUE")
	return true, nil
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

// CreateFakePod creates a KWOK-compatible fake pod with predefined spec
func (kc *KubeClient) CreateFakePod(podName string) error {
	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      podName,
			Namespace: "default",
			Labels: map[string]string{
				"app": podName,
			},
		},
		Spec: corev1.PodSpec{
			Affinity: &corev1.Affinity{
				NodeAffinity: &corev1.NodeAffinity{
					RequiredDuringSchedulingIgnoredDuringExecution: &corev1.NodeSelector{
						NodeSelectorTerms: []corev1.NodeSelectorTerm{
							{
								MatchExpressions: []corev1.NodeSelectorRequirement{
									{
										Key:      "type",
										Operator: corev1.NodeSelectorOpIn,
										Values:   []string{"kwok"},
									},
								},
							},
						},
					},
				},
			},
			Tolerations: []corev1.Toleration{
				{
					Key:      "kwok.x-k8s.io/node",
					Operator: corev1.TolerationOpExists,
					Effect:   corev1.TaintEffectNoSchedule,
				},
			},
			Containers: []corev1.Container{
				{
					Name:  "fake-container",
					Image: "fake-image",
				},
			},
		},
	}

	err := kc.SendPod(pod)
	if err != nil {
		return err
	}
	return nil
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
func (kc *KubeClient) DeleteNode(name string) error {
	return kc.clientset.CoreV1().
		Nodes().
		Delete(context.TODO(), name, metav1.DeleteOptions{})
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

func (kc *KubeClient) DeletePod(podName string) error {
	return kc.clientset.CoreV1().
		Pods("default").
		Delete(context.TODO(), podName, metav1.DeleteOptions{})
}

func (kc *KubeClient) DeletePods(pods []*corev1.Pod) error {
	for _, pod := range pods {
		if err := kc.DeletePod(pod.Name); err != nil {
			return fmt.Errorf("failed to delete pod %s: %w", pod.Name, err)
		}
	}
	return nil
}

func (kc *KubeClient) DeleteAllPods() error {
	return kc.clientset.CoreV1().
		Pods("default").
		DeleteCollection(context.TODO(), metav1.DeleteOptions{}, metav1.ListOptions{})
}

func (kc *KubeClient) DeleteKwokPods() error {
	return kc.clientset.CoreV1().
		Pods("default").
		DeleteCollection(
			context.TODO(),
			metav1.DeleteOptions{},
			metav1.ListOptions{
				LabelSelector: "type=kwok",
			},
		)
}

func (kc *KubeClient) SendPod(pod *corev1.Pod) error {
	_, err := kc.clientset.CoreV1().
		Pods(pod.Namespace).
		Create(context.TODO(), pod, metav1.CreateOptions{})
	return err
}

func (kc *KubeClient) SendNode(node *corev1.Node) error {
	_, err := kc.clientset.CoreV1().
		Nodes().
		Create(context.TODO(), node, metav1.CreateOptions{})
	return err
}
