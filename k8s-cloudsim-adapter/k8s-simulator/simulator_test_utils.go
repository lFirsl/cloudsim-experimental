package simulator

import (
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/uuid"
)

// AddTestPod adds a simulated Pod to the list.
// You can customize it further if needed.
func (sim *K8sSimulator) AddTestPod(name string, namespace string, image string) {
	pod := v1.Pod{
		TypeMeta: metav1.TypeMeta{
			Kind:       "Pod",
			APIVersion: "v1",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name:      name,
			Namespace: namespace,
			UID:       uuid.NewUUID(),
		},
		Spec: v1.PodSpec{
			Containers: []v1.Container{
				{
					Name:  "main",
					Image: image,
				},
			},
			NodeName: "",
		},
		Status: v1.PodStatus{
			Phase: v1.PodPending,
		},
	}

	sim.Pods = append(sim.Pods, pod)
}

// AddTestNode adds a simulated Node with some basic capacity
func (sim *K8sSimulator) AddTestNode(name string, cpu string, memory string) {
	node := v1.Node{
		TypeMeta: metav1.TypeMeta{
			Kind:       "Node",
			APIVersion: "v1",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name: name,
		},
		Status: v1.NodeStatus{
			Allocatable: v1.ResourceList{
				v1.ResourceCPU:    resource.MustParse(cpu),
				v1.ResourceMemory: resource.MustParse(memory),
			},
			Conditions: []v1.NodeCondition{
				{
					Type:   v1.NodeReady,
					Status: v1.ConditionTrue,
				},
			},
		},
	}

	sim.Nodes = append(sim.Nodes, node)
}
