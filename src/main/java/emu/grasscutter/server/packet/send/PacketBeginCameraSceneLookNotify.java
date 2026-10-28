package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.world.Position;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.BeginCameraSceneLookNotifyOuterClass.BeginCameraSceneLookNotify;
import java.util.*;
import lombok.*;

public final class PacketBeginCameraSceneLookNotify extends BasePacket {

    public PacketBeginCameraSceneLookNotify(CameraSceneLookNotify parameters) {
        super(PacketOpcodes.BeginCameraSceneLookNotify);

        this.setData(BeginCameraSceneLookNotify.newBuilder()
                .setLookPos(parameters.lookPos.toProto()).setFollowPos(parameters.followPos.toProto())
                .setDuration(parameters.duration).setIsAllowInput(parameters.isAllowInput)
                .setIsSetFollowPos(parameters.setFollowPos).setIsSetScreenXy(parameters.isScreenXY)
                .setIsRecoverKeepCurrent(parameters.recoverKeepCurrent).setIsChangePlayMode(parameters.isChangePlayMode)
                .setScreenY(parameters.screenY).setScreenX(parameters.screenX)
                .setIsForce(parameters.isForce).setIsForceWalk(parameters.isForceWalk)
                .setEntityId(parameters.entityId).addAllOtherParams(parameters.otherParams)
                .setKeepRotType(parameters.keepRotType).setCustomRadius(parameters.customRadius)
                .setDisableProtect(parameters.disableProtect).setBlendType(parameters.blendType)
                .setBlendDuration(parameters.blendDuration).setIsAbsFollowPos(parameters.absFollowPos));
    }

    @Data
    @NoArgsConstructor
    public static class CameraSceneLookNotify {
        Position lookPos = new Position();
        Position followPos = new Position();
        float duration = 1.0f;
        boolean isAllowInput = false;
        boolean setFollowPos = false;
        boolean isScreenXY = false;
        boolean recoverKeepCurrent = true;
        boolean isForceWalk = false;
        boolean isForce = false;
        boolean isChangePlayMode = false;
        float screenY = 0.0f;
        float screenX = 0.0f;
        int entityId = 0;
        int keepRotType;
        float customRadius;
        int disableProtect;
        int blendType;
        float blendDuration;
        boolean absFollowPos;
        Collection<String> otherParams = new ArrayList<>(0);
    }
}
