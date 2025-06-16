package simulator

import v1 "k8s.io/api/core/v1"

type Scheduler struct {
	ExtenderURL string
	Nodes       []*v1.Node
	Pods        []*v1.Pod
}

func NewScheduler(url string) *Scheduler {
	return &Scheduler{
		ExtenderURL: url,
	}
}

func (s *Scheduler) Schedule(pods []*v1.Pod, nodes []*v1.Node) []*v1.Pod {
	s.Pods = pods
	s.Nodes = nodes

	// for each pod, call extender filter + prioritize and mutate pod.Spec.NodeName
	// You can pull over your existing logic from SchedulePendingPods (adapted for corev1 types)

	// After scheduling, return the updated pods
	return s.Pods
}
