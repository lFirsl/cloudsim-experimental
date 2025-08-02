FROM registry.k8s.io/kube-scheduler:v1.33.0
COPY my-scheduler.yaml /etc/kube-scheduler/my-scheduler.yaml
COPY kubeconfig.yaml /etc/kube-scheduler/kubeconfig.yaml
COPY admin.crt /etc/kube-scheduler/admin.crt
COPY admin.key /etc/kube-scheduler/admin.key
COPY ca.crt /etc/kube-scheduler/ca.crt
CMD ["kube-scheduler", "--config=/etc/kube-scheduler/my-scheduler.yaml", "--secure-port=10260", "--v=3"]