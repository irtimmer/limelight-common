package com.limelight.nvstream.av.audio;

public interface AudioRenderer {
	public boolean streamInitialize();
	
	public void playAudio(byte[] audioData, int offset, int length);
	
	public void streamClosing();
}
