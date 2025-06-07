### CloudSim-Experimental

This is a **Proof of Concept Prototype**.

The goal is to create an extension to CloudSim that allows it to be used 
as a testing harness for Kubernetes Schedulers.

The way we aim to achieve this is by creating an extension to DatacentreBroker
that delegates scheduling/allocation tasks to the Kubernetes scheduler via
an adapter. The adapter aims to simulate a Kubernetes control plane and to communicate
with the kubernetes scheduler via the same API that it would communicate to Kubernetes directly,
thus requiring minimal (if any) changes to the scheduler itself.

##### Prototype Design
![](images/CloudSim_Scheduler_Adapter.png)


### Structure
- `src/main/java/org/example` - Main folder for CloudSim simulations and the
`Live_Kubernetes_Broker` custom class that implements communication to the middleware.
- `k8s-cloudsim-adapter` - the kubernetes control plane simulator, acting as the middleware/adaptor
between CloudSim and the Scheduler
- `k8s-mock-scheduler` - a mock Kubernetes scheduler for testing purposes. Currently implements
a simple first-fit scheduling strategy.


### How to run
You'll need `Go` and `Java JDK21` installed.
1. Within the `k8s-cloudsim-adapter`, run `go run main.go` to start the middleware/adapter.
2. Within the `k8s-mock-scheduler` folder, run `go run main.go` to start the scheduler.
3. Build and Run `src/main/java/org/example/Custom_Broker_Example.java`

The simulation should successfully run, with roughly ~240 cloudlets succeeding.


### To-Do for proper implementation
Since this is a Proof of Concept Prototype, the code itself is messy
and not well thought out. Some parts are also generated via AI in the interest of time.
The objective for this specific prototype is to make something that functionally works to confirm the design is possible.

In the current state, the project functionally works.

The following is a list of changes that need to be made in the "permanent" implementation

- Define communication between the CloudSim Broker and Middleware within a single, blocking HTTP call
  (currently this communication is done via several calls, which makes syncing management difficult)
- Add a structured way to artifically add time to the simulated time (likely via a scheduled, dummy event with a fixed timestamp).