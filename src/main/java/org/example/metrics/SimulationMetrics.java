package org.example.metrics;

import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerDatacenter;
import org.example.kubernetes_broker.PowerDatacenterCustom;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class SimulationMetrics {
    private Instant wallStart;
    private Instant wallEnd;
    private PowerDatacenterCustom powerDatacenter;
    private List<Vm> vms;

    public SimulationMetrics(PowerDatacenterCustom pData,List<Vm> vms) {

        powerDatacenter = pData;
        this.vms = vms;
    }

    public void startWallClock() {
        wallStart = Instant.now();
    }

    public void stopWallClock() {
        wallEnd = Instant.now();
    }

    public long getWallClockMillis() {
        return Duration.between(wallStart, wallEnd).toMillis();
    }

    public long getWallClockSeconds() {
        return Duration.between(wallStart, wallEnd).toSeconds();
    }

    public void printSummary(Double simTime) {
        System.out.println("----- Simulation Metrics -----");
        System.out.println("Simulated Time Elapsed: " + simTime + " units");
        System.out.println("Wall-clock Time Elapsed: " + getWallClockMillis() + " ms (" + getWallClockSeconds() + " s)");
        if(powerDatacenter != null) {
            List<Host> hosts = powerDatacenter.getHostList();

            int numberOfHosts = hosts.size();


            System.out.printf("Energy consumption: %.2f kWh%n", powerDatacenter.getPower() / (3600 * 1000));
            System.out.println("Number of hosts: " + numberOfHosts);
            System.out.println("Time-weighted avg consolidation: " + powerDatacenter.getConsolidationAverage(simTime));

        }
        else{
            System.out.println("ERROR: No PowerDatacenter information provided!");
        }
        if(vms != null) {
            int numberOfVms =vms.size();
            System.out.println("Number of VMs: " + numberOfVms);
        }
        else System.out.println("ERROR: No PowerVM information provided!");


        System.out.println("--------------------------------");
    }
}
