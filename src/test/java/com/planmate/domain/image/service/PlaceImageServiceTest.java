package com.planmate.domain.image.service;

import com.planmate.common.externalAPI.GooglePlaceImageWorker;
import com.planmate.common.externalAPI.GooglePlacePhotoClient;
import com.planmate.common.storage.GooglePlaceImageStorage;
import com.planmate.domain.image.controller.PlaceImageController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlaceImageServiceTest {
    final GooglePlaceImageStorage storage = mock(GooglePlaceImageStorage.class);
    final GooglePlacePhotoClient client = mock(GooglePlacePhotoClient.class);
    final GooglePlaceImageWorker worker = mock(GooglePlaceImageWorker.class);
    final PlaceImageService service = new PlaceImageService(storage, client, worker);
    final byte[] bytes = {1, 2, 3};

    @Test void cachedImageUsesServerStorage() throws Exception {
        when(storage.read("place")).thenReturn(bytes);
        var mvc = MockMvcBuilders.standaloneSetup(new PlaceImageController(service)).build();
        for (String path : new String[]{"/api/image/place/place", "/image/place/place"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().contentType("image/webp"))
                    .andExpect(content().bytes(bytes));
        }
        verifyNoInteractions(client, worker);
    }

    @Test void cacheMissReturnsOriginalAndQueuesStorage() {
        when(client.fetch("place")).thenReturn(ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes));
        var response = service.getImage("place");
        assertThat(response.getBody()).isEqualTo(bytes);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        verify(worker).uploadAsync("place", bytes);
    }

    @Test void rejectedStorageDoesNotPreventOriginalResponse() {
        when(client.fetch("place")).thenReturn(ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(bytes));
        doThrow(new org.springframework.core.task.TaskRejectedException("full")).when(worker).uploadAsync("place", bytes);
        assertThat(service.getImage("place").getBody()).isEqualTo(bytes);
    }

    @Test void missingPhotoReturns404WithoutSaving() {
        assertThat(service.getImage("place").getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(worker);
    }

    @Test void nonImageResponseIsNotServedOrStored() {
        when(client.fetch("place")).thenReturn(ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(bytes));
        assertThat(service.getImage("place").getStatusCode().value()).isEqualTo(502);
        verifyNoInteractions(worker);
    }
}
