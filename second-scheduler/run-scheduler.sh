#!/bin/bash

docker run --rm \
  --network host \
  --name my-scheduler \
  -v $(pwd)/my-scheduler.yaml:/etc/kube-scheduler/my-scheduler.yaml \
  -v $(pwd)/kubeconfig.yaml:/etc/kube-scheduler/kubeconfig.yaml \
  -v $(pwd)/admin.crt:/etc/kube-scheduler/admin.crt \
  -v $(pwd)/admin.key:/etc/kube-scheduler/admin.key \
  -v $(pwd)/ca.crt:/etc/kube-scheduler/ca.crt \
  registry.k8s.io/kube-scheduler:v1.33.0 \
  kube-scheduler \
    --config=/etc/kube-scheduler/my-scheduler.yaml \
    --secure-port=10260 \
    --v=3


# Run this with
# chmod +x run-scheduler.sh
# ./run-scheduler.sh &