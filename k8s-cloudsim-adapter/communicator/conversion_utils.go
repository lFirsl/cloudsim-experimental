package communicator

import (
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func convertToK8sPod(p *CsPod) *corev1.Pod {
	return &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      p.Name,
			Namespace: "default",
		},
		Spec: corev1.PodSpec{
			SchedulerName: p.SchedulerName,
			Containers: []corev1.Container{
				{
					Name: "sim-container",
					Resources: corev1.ResourceRequirements{
						Requests: corev1.ResourceList{
							corev1.ResourceCPU:    *resourceQuantityFromMIPS(p.MIPSReq),
							corev1.ResourceMemory: *resourceQuantityFromMB(p.RAMReq),
						},
					},
				},
			},
		},
	}
}

func convertToK8sNodeList(nodes []CsNode) *corev1.NodeList {
	k8sNodes := []corev1.Node{}
	for _, n := range nodes {
		node := corev1.Node{
			ObjectMeta: metav1.ObjectMeta{
				Name: n.Name,
			},
			Status: corev1.NodeStatus{
				Allocatable: corev1.ResourceList{
					corev1.ResourceCPU:    *resourceQuantityFromMIPS(n.MIPSAval),
					corev1.ResourceMemory: *resourceQuantityFromMB(n.RAMAval),
				},
			},
		}
		k8sNodes = append(k8sNodes, node)
	}
	return &corev1.NodeList{Items: k8sNodes}
}

func convertFromK8sPod(p *corev1.Pod) CsPod {
	// You’ll need to find a way to reverse-map pod.Name to ID, or embed it in annotations
	return CsPod{
		Name:     p.Name,
		Status:   "Scheduled",
		NodeName: p.Spec.NodeName,
		// Add NodeID if available in map/annotation
	}
}

func resourceQuantityFromMIPS(mips int) *resource.Quantity {
	q := resource.NewMilliQuantity(int64(mips), resource.DecimalSI)
	return q
}

func resourceQuantityFromMB(mb int) *resource.Quantity {
	q := resource.NewQuantity(int64(mb)*1024*1024, resource.BinarySI)
	return q
}
