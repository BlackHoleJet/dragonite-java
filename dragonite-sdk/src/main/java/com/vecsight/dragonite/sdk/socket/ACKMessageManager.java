/*
 * The Dragonite Project
 * -------------------------
 * See the LICENSE file in the root directory for license information.
 */


package com.vecsight.dragonite.sdk.socket;

import co.paralleluniverse.common.util.ConcurrentSet;
import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.Strand;
import com.vecsight.dragonite.sdk.msg.types.ACKMessage;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ACKMessageManager {

    private final DragoniteSocket socket;

    private final int MTU;

    private final SendAction action;

    private final Fiber sendThread;

    private final int delayMS;

    private final ConcurrentLinkedDeque<Integer> ackList = new ConcurrentLinkedDeque<Integer>();

    private volatile int receivedSeq = 0;

    private volatile boolean receivedSeqChanged = false;

    private volatile boolean running = true;

    private volatile boolean enableLoopLock = false;

//    private final Object ackLoopLock = new Object();

    protected ACKMessageManager(final DragoniteSocket socket, final SendAction action, final int delayMS, final int MTU) {
        this.socket = socket;
        this.MTU = MTU;
        this.action = action;
        this.delayMS = delayMS;
        sendThread = new Fiber("DS-ACK", () -> {
            try {
                while (running && socket.isAlive()) {
                    List<Integer> ackarray = new ArrayList();
                    while (ackList.size() > 0 && ackarray.size() < 10) {
                        ackarray.add(ackList.remove());
                    }

                        receivedSeqChanged = false;

                        if (ACKMessage.FIXED_LENGTH + ackarray.size() * Integer.BYTES > MTU) {
                            final int payloadIntSize = (MTU - ACKMessage.FIXED_LENGTH) / Integer.BYTES;
                            int msgCount = ackarray.size() / payloadIntSize;
                            if (ackarray.size() % payloadIntSize != 0) {
                                msgCount += 1;
                            }
                            if (msgCount == 0) msgCount = 1;

                            if (msgCount == 1) {
                                sendACKArray(ackarray);
                            } else {
                                int offset = 0, nextLen = payloadIntSize;
                                for (int i = 0; i < msgCount; i++) {
//                                    final int[] acks = Arrays.copyOfRange(ackarray.toArray(), offset, offset + nextLen);
                                    sendACKArray(ackarray);
                                    offset += nextLen;
                                    if (offset + nextLen > ackarray.size()) {
                                        nextLen = ackarray.size() - (msgCount - 1) * payloadIntSize;
                                    }
                                }
                            }
                        } else {
                            sendACKArray(ackarray);
                        }

                    if (receivedSeqChanged) {
                        receivedSeqChanged = false;
                        sendACKArray(new int[]{});
                    }

                    if (enableLoopLock) {
//                        synchronized (ackLoopLock) {
//                            ackLoopLock.notifyAll();
//                        }
                    }
                    Strand.sleep(this.delayMS);
                }
            } catch (final InterruptedException ignored) {
                //okay
            } catch (SuspendExecution suspendExecution) {
                suspendExecution.printStackTrace();
            }
        });
        sendThread.start();
    }

    protected void waitAckLoop() throws InterruptedException {
        enableLoopLock = true;
//        synchronized (ackLoopLock) {
//            ackLoopLock.wait();
//        }
    }

    private int[] toIntArray(final Set<Integer> integerSet) {
        final int[] array = new int[integerSet.size()];
        int i = 0;
        for (final Integer integer : integerSet) {
            array[i++] = integer;
        }
        return array;
    }

    protected void sendACKArray(final List<Integer> ackarray) {
        int[] aa=new int[ackarray.size()];
        for (int i=0;i<aa.length;i++){
            aa[i]=ackarray.get(i);
        }
        sendACKArray(aa);
    }
    protected void sendACKArray(final int[] ackarray) {
        //System.out.println("SEND ACK " + System.currentTimeMillis());
        final ACKMessage ackMessage = new ACKMessage(ackarray, receivedSeq);
        try {
            action.sendPacket(ackMessage.toBytes());
        } catch (IOException | InterruptedException ignored) {
        }
    }

    protected boolean addACK(final int seq) {
        synchronized (ackList) {
            return ackList.add(seq);
        }
    }

    protected void updateReceivedSeq(final int receivedSeq) {
        this.receivedSeq = receivedSeq;
        this.receivedSeqChanged = true;
    }

    protected void close() {
        running = false;
        sendThread.interrupt();
        ackList.clear();
//        synchronized (ackLoopLock) {
//            ackLoopLock.notifyAll();
//        }
    }

}
