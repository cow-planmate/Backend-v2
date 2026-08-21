package com.planmate.domain.notification;

import build.buf.gen.planmate.notification.v1.ActionRef;
import build.buf.gen.planmate.notification.v1.ActorSnapshot;
import build.buf.gen.planmate.notification.v1.NotificationRequested;
import build.buf.gen.planmate.notification.v1.NotificationType;
import build.buf.gen.planmate.notification.v1.ResourceRef;
import com.google.protobuf.Timestamp;
import com.planmate.domain.notification.outbox.NotificationOutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CollaborationKafkaNotificationPublisher {
    private final NotificationOutboxWriter outbox;

    public void requested(UUID recipientId, UUID actorId, String actorName, UUID planId, String planName,
                          long collaborationRequestId, boolean invite) {
        outbox.publish(base(recipientId, actorId, actorName, planId, planName)
                .setType(invite
                        ? NotificationType.NOTIFICATION_TYPE_PLAN_INVITED
                        : NotificationType.NOTIFICATION_TYPE_PLAN_EDIT_ACCESS_REQUESTED)
                .setAction(ActionRef.newBuilder()
                        .setRoute("COLLABORATION_REQUESTS")
                        .putParameters("collaborationRequestId", Long.toString(collaborationRequestId)))
                .build());
    }

    public void result(UUID recipientId, UUID actorId, String actorName, UUID planId, String planName,
                       long collaborationRequestId, boolean accepted) {
        outbox.publish(base(recipientId, actorId, actorName, planId, planName)
                .setType(accepted
                        ? NotificationType.NOTIFICATION_TYPE_PLAN_REQUEST_ACCEPTED
                        : NotificationType.NOTIFICATION_TYPE_PLAN_REQUEST_REJECTED)
                .setAction(ActionRef.newBuilder()
                        .setRoute("PLAN_DETAIL")
                        .putParameters("planId", planId.toString())
                        .putParameters("collaborationRequestId", Long.toString(collaborationRequestId)))
                .build());
    }

    private NotificationRequested.Builder base(UUID recipientId, UUID actorId, String actorName,
                                               UUID planId, String planName) {
        Instant now = Instant.now();
        return NotificationRequested.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setProducer("backend-v2")
                .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                .setRecipientUserId(recipientId.toString())
                .setActor(ActorSnapshot.newBuilder()
                        .setUserId(actorId.toString()).setDisplayName(actorName))
                .setResource(ResourceRef.newBuilder()
                        .setType("PLAN").setId(planId.toString()).setDisplayName(planName));
    }
}
