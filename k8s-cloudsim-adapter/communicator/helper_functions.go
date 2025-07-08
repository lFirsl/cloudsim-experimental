package communicator

import (
	"fmt"
	"regexp"
	"strconv"
)

func nodesEqual(a, b CsNode) bool {
	return a.ID == b.ID &&
		a.MIPSAval == b.MIPSAval &&
		a.RAMAval == b.RAMAval &&
		a.BW == b.BW &&
		a.Size == b.Size &&
		a.Pes == b.Pes &&
		a.Type == b.Type
}

func extractNodeID(name string) (int, error) {
	re := regexp.MustCompile(`csnode-(\d+)`)
	matches := re.FindStringSubmatch(name)
	if len(matches) == 2 {
		return strconv.Atoi(matches[1])
	}
	return 0, fmt.Errorf("not a valid csnode name: %s", name)
}
