package com.limelight.nvstream.av.audio;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.ByteBufferDescriptor;
import com.limelight.nvstream.av.RtpPacket;
import com.limelight.nvstream.av.SequenceHelper;

public class AudioDepacketizer {
	
	// Direct submit state
	private AudioDecoderRenderer renderer;
	
	// Cached objects
	private ByteBufferDescriptor cachedDesc = new ByteBufferDescriptor(null, 0, 0);
	
	// Sequencing state
	private short lastSequenceNumber;
	
	public AudioDepacketizer(AudioDecoderRenderer renderer)
	{
		this.renderer = renderer;
	}

	public void decodeInputData(RtpPacket packet)
	{
		short seq = packet.getRtpSequenceNumber();
		
		// Toss out the current NAL if we receive a packet that is
		// out of sequence
		if (lastSequenceNumber != 0 &&
			(short)(lastSequenceNumber + 1) != seq)
		{
			LimeLog.warning("Received OOS audio data (expected "+(lastSequenceNumber + 1)+", got "+seq+")");
			
			// Only tell the decoder if we got packets ahead of what we expected
			// If the packet is behind the current sequence number, drop it
			if (!SequenceHelper.isBeforeSigned(seq, (short)(lastSequenceNumber + 1), false)) {
				renderer.playAudio(null, 0, 0);
			}
			else {
				return;
			}
		}
		
		lastSequenceNumber = seq;
		
		// This is all the depacketizing we need to do
		packet.initializePayloadDescriptor(cachedDesc);
		renderer.playAudio(cachedDesc.data, cachedDesc.offset, cachedDesc.length);
	}
}
