# COUBES

**COUBES** (Container Orchestration Universal Benchmark for Evaluating Schedulers) is a framework that integrates [CloudSim 7G](https://github.com/Cloudslab/cloudsim) with lightweight Kubernetes clusters to enable reproducible, multi-metric benchmarking of container orchestration schedulers.

This repository contains the current **proof-of-concept implementation**, developed as part of an MSci Project (2024–2025). It will transition towards a more mature and extensible version at a later date.

## Overview

The aim of COUBES is to provide a universal testing harness for Kubernetes schedulers that avoids the need for dual implementations. Instead of re-implementing scheduling logic inside CloudSim, COUBES delegates scheduling decisions to a live Kubernetes scheduler via an adapter layer.

- A custom CloudSim broker (`Live_Kubernetes_Broker_EX`) extends the default `DatacentreBroker`.
- The broker forwards CloudSim resources (VMs and Cloudlets) to an adapter written in Go. This can be found in the `k8s-cloudsim-adapter` folder.
- The adapter translates these into Kubernetes equivalents (Nodes and Pods), using [KWOK](https://kwok.sigs.k8s.io/) for lightweight cluster emulation.
- The native Kubernetes scheduler performs scheduling as usual.
- Results are returned from KWOK to the adapter, then mapped back into CloudSim’s resource model so the simulation can proceed.

## Current Status

- Currently supports basic scenarios with the Kubernetes Default Scheduler.
- Implements a scoring system for comparing schedulers across multiple metrics.
- Evaluated using three test scenarios: undercrowding, fragmentation, and performance vs efficiency.

Future development will focus on:
- Broader test scenarios and additional metrics (e.g. latency, throughput).
- Support for metric-aware schedulers.
- Scalability evaluation with larger simulated clusters.
- Integration with other orchestration frameworks beyond Kubernetes.

---

*This project is under active development and is part of academic research into reproducible benchmarking for container orchestration schedulers.*

### Ideal Design
This diagram showcases the ideal design that this repository aims to follow:
![](images/Ambitious_Design.png)

### Proof-of-Concept Prototype Implementation
This diagram showcases the implementation design of the Proof-of-Concept Prototype:
![](images/Implementation_Design.png)


### Structure
- `src/main/java/org/example` - Main folder for CloudSim simulations and the
`Live_Kubernetes_Broker` custom class that implements communication to the middleware.
- `k8s-cloudsim-adapter` - the kubernetes control plane simulator, acting as the middleware/adaptor
between CloudSim and Kubernetes.


### How to run
You'll need `Go`, `KWOK` and `Java JDK21` installed.
1. Within the `k8s-cloudsim-adapter`, run `go run main.go` to start the middleware/adapter.
2. From the CLI, prepare the KWOK cluster using `kwokctl create cluster` then `kubectl cluster-info --context kwok-kwok`
4. Build and Run `src/main/java/org/example/Custom_Broker_Example.java`

The simulation should successfully run, with roughly ~240 cloudlets succeeding.
