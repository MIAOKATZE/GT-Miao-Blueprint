// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import cpw.mods.fml.common.network.simpleimpl.*;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.INetHandler;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintLimits;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

/** Only the saved source SHA and placement controls are present on a hot request. */
public final class HologramPrivateNativeWire {
    private HologramPrivateNativeWire(){}
    public static final int BEGIN=1, DATA=2, CANCEL=3, NEED=1, PROGRESS=2, COMPLETE=3, FAILED=4, CANCELLED=5;
    public static final int FRAME=HologramPrivateNativeCache.FRAME;
    private static final int VERSION=1;
    private static volatile SimpleNetworkWrapper channel;
    private static final HologramRequestGate pending=new HologramRequestGate(3,64);
    private static volatile java.util.function.BiConsumer<Reply,INetHandler> receiver;
    public static class Request implements IMessage{
        public int op,dimension,x,y,z,sources,offset,length;public boolean voucher;
        public long intent;public UUID id,tool;public String sha="";public int[] pose=new int[0],bindingSlots=new int[0];public byte[] bytes=new byte[0];
        public Request(){}
        public void fromBytes(ByteBuf b){
            ok(b.readUnsignedByte()==VERSION);op=b.readUnsignedByte();id=uuid(b);
            if(op==BEGIN){
                intent=b.readLong();tool=uuid(b);dimension=b.readInt();x=b.readInt();y=b.readInt();z=b.readInt();sources=b.readUnsignedByte();
                int v=b.readUnsignedByte();ok(v<=1);voucher=v==1;length=b.readInt();ok(intent>0&&length>0&&length<=NativeBlueprintLimits.FILE_BYTES);
                StringBuilder h=new StringBuilder(64);for(int i=0;i<32;i++){int n=b.readUnsignedByte();h.append(Character.forDigit(n>>>4,16));h.append(Character.forDigit(n&15,16));}sha=h.toString();
                int n=b.readUnsignedByte();ok(n<=HologramPreviewPose.MAX_RECIPE_LENGTH);pose=new int[n];for(int i=0;i<n;i++){pose[i]=b.readUnsignedByte();ok(pose[i]<=1);}
                if(b.isReadable()){int count=b.readUnsignedShort();ok(count>0&&count<=HologramSavedBindingChoices.MAX_CHOICES&&b.readableBytes()==count*4);
                    bindingSlots=new int[count];for(int i=0;i<count;i++)bindingSlots[i]=b.readInt();ok(HologramSavedBindingChoices.valid(bindingSlots));}
            }else if(op==DATA){
                offset=b.readInt();int n=b.readUnsignedShort();ok(offset>=0&&n>0&&n<=FRAME&&b.readableBytes()==n);bytes=new byte[n];b.readBytes(bytes);
            }else ok(op==CANCEL);
            ok(!b.isReadable());
        }
        public void toBytes(ByteBuf b){
            b.writeByte(VERSION).writeByte(op);uuid(b,id);
            if(op==BEGIN){ok(sha!=null&&sha.matches("[0-9a-f]{64}")&&pose.length<=HologramPreviewPose.MAX_RECIPE_LENGTH);
                b.writeLong(intent);uuid(b,tool);b.writeInt(dimension).writeInt(x).writeInt(y).writeInt(z).writeByte(sources).writeByte(voucher?1:0).writeInt(length);
                for(int i=0;i<64;i+=2)b.writeByte(Integer.parseInt(sha.substring(i,i+2),16));b.writeByte(pose.length);for(int axis:pose)b.writeByte(axis);
                ok(HologramSavedBindingChoices.valid(bindingSlots));if(bindingSlots.length>0){b.writeShort(bindingSlots.length);for(int choice:bindingSlots)b.writeInt(choice);}
            }else if(op==DATA){ok(bytes!=null&&bytes.length>0&&bytes.length<=FRAME);b.writeInt(offset).writeShort(bytes.length).writeBytes(bytes);}else ok(op==CANCEL);
        }
    }
    public static class Reply implements IMessage{
        public int op,offset,placed,satisfied,total;public long seq;public UUID id;public String message="";
        public Reply(){}
        Reply(int op,UUID id,int offset,int placed,int satisfied,int total,long seq,String message){this.op=op;this.id=id;this.offset=offset;this.placed=placed;this.satisfied=satisfied;this.total=total;this.seq=seq;this.message=message==null?"":message;}
        public void fromBytes(ByteBuf b){ok(b.readUnsignedByte()==VERSION);op=b.readUnsignedByte();ok(op>=NEED&&op<=CANCELLED);id=uuid(b);
            offset=b.readInt();placed=b.readInt();satisfied=b.readInt();total=b.readInt();seq=b.readLong();
            int n=b.readUnsignedShort();ok(n<=768&&b.readableBytes()==n&&offset>=0&&placed>=0&&satisfied>=0&&total>=0&&(long)placed+satisfied<=total&&seq>0);
            byte[] text=new byte[n];b.readBytes(text);message=new String(text,StandardCharsets.UTF_8);}
        public void toBytes(ByteBuf b){b.writeByte(VERSION).writeByte(op);uuid(b,id);b.writeInt(offset).writeInt(placed).writeInt(satisfied).writeInt(total).writeLong(seq);
            byte[] text=message.getBytes(StandardCharsets.UTF_8);if(text.length>768)text="原生施工失败".getBytes(StandardCharsets.UTF_8);b.writeShort(text.length).writeBytes(text);}
    }
    private static void ok(boolean b){if(!b)throw new IllegalArgumentException("Invalid saved blueprint message");}
    private static UUID uuid(ByteBuf b){return new UUID(b.readLong(),b.readLong());}
    private static void uuid(ByteBuf b,UUID id){b.writeLong(id.getMostSignificantBits()).writeLong(id.getLeastSignificantBits());}
    static void register(SimpleNetworkWrapper wire){wire.registerMessage(Server.class,Request.class,14,Side.SERVER);
        wire.registerMessage(Client.class,Reply.class,15,Side.CLIENT);channel=wire;}
    public static boolean available(){return channel!=null;}
    public static void listen(java.util.function.BiConsumer<Reply,INetHandler> r){receiver=r;}
    public static void send(Request m){if(channel!=null)channel.sendToServer(m);}
    static void reply(EntityPlayerMP p,Reply m){if(channel!=null)channel.sendTo(m,p);}
    public static class Server implements IMessageHandler<Request,IMessage>{
        public IMessage onMessage(Request m,MessageContext c){var handler=c.getServerHandler();var permit=pending.acquire(handler);if(permit==null)return null;
            ServerTaskScheduler.scheduleServerTask(()->{try{HologramPrivateNativeServer.receive(HologramWorkspace.captureActionEndpoint(handler),m);}finally{permit.close();}});return null;}
    }
    public static class Client implements IMessageHandler<Reply,IMessage>{
        public IMessage onMessage(Reply m,MessageContext c){var r=receiver;if(r!=null)r.accept(m,c.netHandler);return null;}
    }
}
