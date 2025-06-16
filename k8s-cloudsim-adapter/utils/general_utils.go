package utils

import "strings"

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
