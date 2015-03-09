package com.limelight.nvstream.av.audio;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.ByteBufferDescriptor;
import com.limelight.nvstream.av.buffer.AbstractPopulatedBufferList;
import com.limelight.nvstream.av.buffer.AtomicPopulatedBufferList;

public abstract class AudioAsyncDecoderRenderer implements AudioDecoderRenderer {
	
	private static final int DU_LIMIT = 30;
	private AbstractPopulatedBufferList<ByteBufferDescriptor> decodedUnits;
	
	private Thread playerThread;
	
	public AudioAsyncDecoderRenderer() {
		decodedUnits = new AtomicPopulatedBufferList<ByteBufferDescriptor>(DU_LIMIT, new AbstractPopulatedBufferList.BufferFactory() {
			public Object createFreeBuffer() {
				return new ByteBufferDescriptor(new byte[getOutputBufferSize()], 0, getOutputBufferSize());
			}

			public void cleanupObject(Object o) {
				// Nothing to do
			}
		});
	}

	@Override
	public void playAudio(byte[] data, int off, int len) {
		ByteBufferDescriptor bb = decodedUnits.pollFreeObject();
		if (bb == null) {
			LimeLog.warning("Audio player too slow! Forced to drop decoded samples");
			decodedUnits.clearPopulatedObjects();
			bb = decodedUnits.pollFreeObject();
			if (bb == null) {
				LimeLog.severe("Audio player is leaking buffers!");
				return;
			}
		}
		
		int decodeLen = decode(data, off, len, bb.data);
			
		if (decodeLen > 0) {
			// Return value of decode is frames (shorts) decoded per channel
			decodeLen *= 2*getChannelCount();
			
			bb.length = decodeLen;
			decodedUnits.addPopulatedObject(bb);
		} else {
			decodedUnits.freePopulatedObject(bb);
		}
	}

	@Override
	public boolean streamInitialize() {
		// Decoder thread
		playerThread = new Thread() {
			@Override
			public void run() {
				
				while (!isInterrupted())
				{
					ByteBufferDescriptor samples;
					
					try {
						samples = decodedUnits.takePopulatedObject();
					} catch (InterruptedException e) {
						return;
					}
					
					playDecodedAudio(samples.data, samples.offset, samples.length);
					decodedUnits.freePopulatedObject(samples);
				}
			}
		};
		playerThread.setName("Audio - Player");
		playerThread.setPriority(Thread.NORM_PRIORITY + 2);
		playerThread.start();
		
		return true;
	}

	@Override
	public void streamClosing() {
		playerThread.interrupt();
	}
	
	protected abstract int decode(byte[] audioData, int offset, int len, byte[] decodedData);
	
	protected abstract void playDecodedAudio(byte[] audioData, int offset, int length);
	
	protected abstract int getOutputBufferSize();
	
	protected abstract int getChannelCount();
	
}
