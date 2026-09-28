package com.chemrob.medadherence.core;

/**
 * What the on-device AI (ML Kit face + pose detection) saw in one camera frame, reduced to the
 * numbers the intake rules need. Distances are normalised by the face width so they don't depend on
 * how close the phone is. NaN means "not measured in this frame".
 */
public final class FrameObs {
    public long timeMs;
    public int faces;                 // number of faces found
    public double yaw = Double.NaN;   // head turn left/right, degrees (0 = facing the camera)
    public double pitch = Double.NaN; // head up/down, degrees (positive = looking up / tilted back)
    public double eyesOpen = Double.NaN;   // lower of the two eye-open probabilities, 0..1
    public double mouthOpen = Double.NaN;  // lip gap / mouth width
    public double handToMouth = Double.NaN; // nearest wrist or finger to the mouth, in face widths
    public boolean handVisible;       // a wrist or finger is confidently in the frame
    public double brightness = Double.NaN; // mean luma 0..1
    public double[] signature;        // face geometry signature (see FaceSignature), or null
    public double faceSim = Double.NaN; // face-recognition similarity to the enrolled patient (see FaceMatch)

    public boolean oneFace() { return faces == 1; }
    public boolean frontal() { return oneFace() && !Double.isNaN(yaw) && Math.abs(yaw) <= 20; }
}
