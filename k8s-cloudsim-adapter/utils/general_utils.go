package utils

import (
	corev1 "k8s.io/api/core/v1"
	"strings"
)

// SplitPath is a helper function to robustly split a URL path into segments,
// handling leading/trailing slashes and empty segments.
func SplitPath(path string) []string {
	parts := strings.Split(path, "/")
	var cleanedParts []string
	for _, part := range parts {
		if part != "" {
			cleanedParts = append(cleanedParts, part)
		}
	}
	return cleanedParts
}

func ToPointerSlice(nodes []corev1.Node) []*corev1.Node {
	result := make([]*corev1.Node, len(nodes))
	for i := range nodes {
		result[i] = &nodes[i]
	}
	return result
}
