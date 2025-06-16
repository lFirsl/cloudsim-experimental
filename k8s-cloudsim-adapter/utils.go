package main

import (
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"strings"
)

// splitPath is a helper function to robustly split a URL path into segments,
// handling leading/trailing slashes and empty segments.
func splitPath(path string) []string {
	parts := strings.Split(path, "/")
	var cleanedParts []string
	for _, part := range parts {
		if part != "" {
			cleanedParts = append(cleanedParts, part)
		}
	}
	return cleanedParts
}

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

func resourceQuantityFromMIPS(mips int) *resource.Quantity {
	q := resource.NewMilliQuantity(int64(mips), resource.DecimalSI)
	return q
}

func resourceQuantityFromMB(mb int) *resource.Quantity {
	q := resource.NewQuantity(int64(mb)*1024*1024, resource.BinarySI)
	return q
}
