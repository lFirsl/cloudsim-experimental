package kube_client

import (
	"context"
	"fmt"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/utils/pointer"
	"log"
)

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

func (kc *KubeClient) AreAllPodsScheduled(namespace string) (bool, error) {
	pods, err := kc.GetPods(namespace)
	if err != nil {
		return false, err
	}

	for _, pod := range pods {
		if pod.Spec.NodeName != "" {
			continue // Already scheduled
		}

		// Check if marked as Unschedulable
		unschedulable := false
		for _, cond := range pod.Status.Conditions {
			if cond.Type == corev1.PodScheduled &&
				cond.Status == corev1.ConditionFalse &&
				cond.Reason == "Unschedulable" {
				unschedulable = true
				break
			}
		}

		if !unschedulable {
			// Still pending
			log.Printf("Pod %s is still pending", pod.Name)
			return false, nil
		}
	}

	log.Println("All pods are either scheduled or unschedulable")
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

func (kc *KubeClient) DeletePod(podName string) error {
	return kc.clientset.CoreV1().
		Pods("default").
		Delete(context.TODO(), podName, metav1.DeleteOptions{
			GracePeriodSeconds: pointer.Int64(0),
		})
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
