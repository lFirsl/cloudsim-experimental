package org.example.metrics;

import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerDatacenter;

import java.time.Duration;
import java.time.Instant;

public class SimulationMetrics {
    private Instant wallStart;
    private Instant wallEnd;
    private PowerDatacenter powerDatacenter;

    public SimulationMetrics(PowerDatacenter pData) {
        powerDatacenter = pData;
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
        if(powerDatacenter != null) System.out.println("Total energy consumed (kWh): " + powerDatacenter.getPower());
        System.out.println("--------------------------------");
    }
}
