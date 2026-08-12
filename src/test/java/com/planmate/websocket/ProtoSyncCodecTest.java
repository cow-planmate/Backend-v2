package com.planmate.websocket;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.planmate.domain.timetable.enums.BlockCategory;
import com.sharedsync.shared.codec.ProtoSyncCodec;
import com.sharedsync.shared.codec.SyncDescriptors;
import com.sharedsync.shared.codec.SyncOutbound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.MimeTypeUtils;
import sharedsync.dto.TimeTablePlaceBlockDto;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProtoSyncCodec 이 실제로 유효한 ServerFrame 바이트를 만드는지 검증한다.
 *
 * content-type 이 application/octet-stream 인지도 함께 본다 — StompSubProtocolHandler 는
 * 이 값일 때만 BinaryMessage 로 내보내고, 아니면 바이트를 UTF-8 로 디코딩해 조용히 손상시킨다.
 */
class ProtoSyncCodecTest {

    private static SyncDescriptors descriptors;
    private static ProtoSyncCodec codec;

    @BeforeAll
    static void setUp() {
        descriptors = new SyncDescriptors();
        codec = new ProtoSyncCodec(descriptors);
    }

    @Test
    @DisplayName("content-type 이 application/octet-stream 이다 (바이너리 프레임 조건)")
    void contentTypeTriggersBinaryFraming() {
        assertThat(codec.contentType()).isEqualTo(MimeTypeUtils.APPLICATION_OCTET_STREAM);
    }

    @Test
    @DisplayName("편집 브로드캐스트가 ServerFrame.sync 로 인코딩되고 다시 파싱된다")
    void entitiesEncodeToParsableServerFrame() throws Exception {
        TimeTablePlaceBlockDto dto = new TimeTablePlaceBlockDto();
        set(dto, "blockId", 42L);
        set(dto, "timeTableId", 7L);
        set(dto, "placeName", "성산일출봉");
        set(dto, "latitude", new BigDecimal("33.45810000"));
        set(dto, "blockStartTime", LocalTime.of(9, 30));
        set(dto, "blockCategory", BlockCategory.ATTRACTION);

        byte[] wire = codec.encode(new SyncOutbound.Entities(
                "evt-1", "create", "timetableplaceblock", false,
                "timeTablePlaceBlockDtos", List.of(dto)));

        Descriptor frameDesc = descriptors.message("ServerFrame");
        DynamicMessage frame = DynamicMessage.parseFrom(frameDesc, wire);

        assertThat(frame.hasField(frameDesc.findFieldByName("sync"))).isTrue();

        DynamicMessage sync = (DynamicMessage) frame.getField(frameDesc.findFieldByName("sync"));
        Descriptor syncDesc = descriptors.message("SyncEvent");
        assertThat(sync.getField(syncDesc.findFieldByName("event_id"))).isEqualTo("evt-1");
        assertThat(sync.getField(syncDesc.findFieldByName("action")).toString())
                .isEqualTo("SYNC_ACTION_CREATE");
        assertThat(sync.getField(syncDesc.findFieldByName("is_undo_redo"))).isEqualTo(false);

        DynamicMessage list = (DynamicMessage) sync.getField(
                syncDesc.findFieldByName("time_table_place_blocks"));
        Descriptor listDesc = descriptors.message("TimeTablePlaceBlockList");
        assertThat(list.getRepeatedFieldCount(listDesc.findFieldByName("items"))).isEqualTo(1);

        DynamicMessage item = (DynamicMessage) list.getRepeatedField(listDesc.findFieldByName("items"), 0);
        Descriptor itemDesc = descriptors.message("TimeTablePlaceBlock");
        assertThat(item.getField(itemDesc.findFieldByName("place_name"))).isEqualTo("성산일출봉");
        assertThat(item.getField(itemDesc.findFieldByName("latitude"))).isEqualTo("33.45810000");
        assertThat(item.getField(itemDesc.findFieldByName("block_start_time"))).isEqualTo("09:30:00");
        assertThat(item.getField(itemDesc.findFieldByName("block_category")).toString())
                .isEqualTo("BLOCK_CATEGORY_ATTRACTION");

        // 보내지 않은 필드는 미설정으로 남아야 부분 병합이 성립한다
        assertThat(item.hasField(itemDesc.findFieldByName("memo"))).isFalse();

        System.out.println("[SIZE] ServerFrame(sync, 블록1개) = " + wire.length + " bytes");
    }

    @Test
    @DisplayName("undo 브로드캐스트는 같은 SyncEvent 에 is_undo_redo=true 로만 구분된다")
    void undoUsesSameShapeWithFlag() throws Exception {
        TimeTablePlaceBlockDto dto = new TimeTablePlaceBlockDto();
        set(dto, "blockId", 1L);

        byte[] wire = codec.encode(new SyncOutbound.Entities(
                "", "delete", "timetableplaceblock", true,
                "timeTablePlaceBlockDtos", List.of(dto)));

        Descriptor frameDesc = descriptors.message("ServerFrame");
        DynamicMessage frame = DynamicMessage.parseFrom(frameDesc, wire);
        DynamicMessage sync = (DynamicMessage) frame.getField(frameDesc.findFieldByName("sync"));

        assertThat(sync.getField(descriptors.message("SyncEvent").findFieldByName("is_undo_redo")))
                .isEqualTo(true);
    }

    @Test
    @DisplayName("프레즌스가 ServerFrame.presence 로 인코딩되고 사용자 목록이 보존된다")
    void presenceEncodes() throws Exception {
        byte[] wire = codec.encode(new SyncOutbound.Presence(
                "create", "user-1",
                Map.of("nickname", "민트", "email", "a@b.com"),
                List.of(Map.of("uid", "user-1", "userInfo", Map.of("nickname", "민트")),
                        Map.of("uid", "user-2", "userInfo", Map.of("nickname", "코우")))));

        Descriptor frameDesc = descriptors.message("ServerFrame");
        DynamicMessage frame = DynamicMessage.parseFrom(frameDesc, wire);
        assertThat(frame.hasField(frameDesc.findFieldByName("presence"))).isTrue();

        DynamicMessage presence = (DynamicMessage) frame.getField(frameDesc.findFieldByName("presence"));
        Descriptor presenceDesc = descriptors.message("PresenceEvent");
        assertThat(presence.getField(presenceDesc.findFieldByName("uid"))).isEqualTo("user-1");
        assertThat(presence.getRepeatedFieldCount(presenceDesc.findFieldByName("users"))).isEqualTo(2);
    }

    @Test
    @DisplayName("Hello 프레임이 서버 스키마 해시를 실어 보낸다")
    void helloCarriesSchemaHash() throws Exception {
        byte[] wire = codec.encodeHello();
        Descriptor frameDesc = descriptors.message("ServerFrame");
        DynamicMessage frame = DynamicMessage.parseFrom(frameDesc, wire);

        DynamicMessage hello = (DynamicMessage) frame.getField(frameDesc.findFieldByName("hello"));
        assertThat(hello.getField(descriptors.message("Hello").findFieldByName("schema_hash")))
                .isEqualTo(descriptors.getSchemaHash());
    }

    @Test
    @DisplayName("SyncOutbound 가 아닌 페이로드는 조용히 넘어가지 않고 명확히 실패한다")
    void rejectsUnknownPayload() {
        assertThatThrownBy(() -> codec.encode(Map.of("entity", "plan")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SyncOutbound");
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
