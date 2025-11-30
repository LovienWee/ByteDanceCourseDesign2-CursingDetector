package com.example.cursingdetector.model;

public class ProfanityHit {
    public String word;
    public double startSec;
    public double endSec;
    public String lineText;

    public ProfanityHit(String word, double startSec, double endSec, String lineText) {
        this.word = word;
        this.startSec = startSec;
        this.endSec = endSec;
        this.lineText = lineText;
    }
}
