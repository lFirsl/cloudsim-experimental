package org.example.kubernetes_broker;

import org.cloudbus.cloudsim.UtilizationModel;

import java.util.HashMap;
import java.util.Random;

public class UtilizationModelSlice implements UtilizationModel {
    int PEs;
    public UtilizationModelSlice(int slice) {
        this.PEs = slice;
    }

    public double getUtilization(double time) {
        return Math.min(0,1/PEs);
    }
}
