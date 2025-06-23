package kube_client

import (
	"context"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
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

	_, err := kc.clientset.CoreV1().
		Pods("default").
		Create(context.TODO(), pod, metav1.CreateOptions{})
	return err
}

func (kc *KubeClient) DeletePod(podName string) error {
	return kc.clientset.CoreV1().
		Pods("default").
		Delete(context.TODO(), podName, metav1.DeleteOptions{})
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

	_, err := kc.clientset.CoreV1().Nodes().Create(context.TODO(), node, metav1.CreateOptions{})
	return err
}
func (kc *KubeClient) DeleteNode(name string) error {
	return kc.clientset.CoreV1().
		Nodes().
		Delete(context.TODO(), name, metav1.DeleteOptions{})
}
