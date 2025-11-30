package com.example.cursingdetector.model;

public class TranscriptSegment {
    public double startSec;
    public double endSec;
    public String text;

    public TranscriptSegment(double startSec, double endSec, String text) {
        this.startSec = startSec;
        this.endSec = endSec;
        this.text = text;
    }
}
