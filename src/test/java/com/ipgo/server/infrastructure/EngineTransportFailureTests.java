package com.ipgo.server.infrastructure;

import com.ipgo.server.service.EngineJobs;
import com.ipgo.server.service.EngineResults;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EngineTransportFailureTests {
    final EngineJobs jobs = mock(EngineJobs.class);
    final EngineResults results = mock(EngineResults.class);
    final SqsClient sqs = mock(SqsClient.class);
    EngineTransport transport;
    SqsClient unusedClient;

    @BeforeEach void setup() {
        transport = new EngineTransport(jobs, results, "https://queue.example.test/jobs",
                "https://queue.example.test/results", "ap-northeast-2", "http://127.0.0.1:9");
        unusedClient = transport.engineSqsClient();
        ReflectionTestUtils.setField(transport, "sqs", sqs);
        when(jobs.expired()).thenReturn(List.of());
        when(jobs.due()).thenReturn(List.of());
        when(sqs.receiveMessage(org.mockito.ArgumentMatchers.<Consumer<ReceiveMessageRequest.Builder>>any()))
                .thenReturn(ReceiveMessageResponse.builder().messages(Message.builder()
                        .body("synthetic-result").receiptHandle("synthetic-receipt").build()).build());
    }
    @AfterEach void closeUnusedClient() { unusedClient.close(); }

    @Test void failedTransactionalResultNeverAcknowledgesTheMessage() {
        doThrow(new IllegalStateException("synthetic storage failure")).when(results).accept("synthetic-result");
        transport.tick();
        verify(results).accept("synthetic-result");
        verify(sqs, never()).deleteMessage(org.mockito.ArgumentMatchers.<Consumer<DeleteMessageRequest.Builder>>any());
    }

    @Test void ackFailureAllowsRedeliveryAndAckStillFollowsTheResultBoundary() {
        when(sqs.deleteMessage(org.mockito.ArgumentMatchers.<Consumer<DeleteMessageRequest.Builder>>any()))
                .thenThrow(new IllegalStateException("synthetic ACK failure"))
                .thenReturn(DeleteMessageResponse.builder().build());
        transport.tick();
        transport.tick();
        var order = inOrder(results, sqs);
        order.verify(results).accept("synthetic-result");
        order.verify(sqs).deleteMessage(org.mockito.ArgumentMatchers.<Consumer<DeleteMessageRequest.Builder>>any());
        order.verify(results).accept("synthetic-result");
        order.verify(sqs).deleteMessage(org.mockito.ArgumentMatchers.<Consumer<DeleteMessageRequest.Builder>>any());
    }

    @Test void failedDispatchDoesNotMarkSentAndNextTickRetriesTheSameJob() {
        var job = mock(EngineJobs.Job.class);
        when(job.id()).thenReturn("synthetic-job");
        when(jobs.due()).thenReturn(List.of(job));
        when(jobs.claim("synthetic-job")).thenReturn(true);
        when(jobs.envelope(job)).thenReturn("synthetic-envelope");
        var sentBodies = new java.util.ArrayList<String>();
        when(sqs.sendMessage(org.mockito.ArgumentMatchers.<Consumer<SendMessageRequest.Builder>>any()))
                .thenAnswer(invocation -> {
                    Consumer<SendMessageRequest.Builder> fill = invocation.getArgument(0);
                    var request = SendMessageRequest.builder();
                    fill.accept(request);
                    sentBodies.add(request.build().messageBody());
                    if (sentBodies.size() == 1) throw new IllegalStateException("synthetic send failure");
                    return SendMessageResponse.builder().build();
                });
        transport.tick();
        verify(jobs, never()).sent("synthetic-job");
        transport.tick();
        verify(jobs, times(2)).claim("synthetic-job");
        verify(jobs, times(2)).envelope(job);
        verify(jobs).sent("synthetic-job");
        org.assertj.core.api.Assertions.assertThat(sentBodies).containsExactly("synthetic-envelope", "synthetic-envelope");
    }
}
