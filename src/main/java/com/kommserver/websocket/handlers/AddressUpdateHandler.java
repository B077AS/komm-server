package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Installation;
import com.kommserver.repository.InstallationRepository;
import com.kommserver.sfu.SfuLauncher;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.AddressUpdatePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The hub sends the address players use for this server every time the server connects, so a changed IP (a home
 * connection, say) reaches it without a new activation. It is saved, and the media server is told: if the address it
 * advertises for voice is no longer the right one it is restarted. That costs nobody a call: a changed IP has already
 * broken any live media, and right after a boot nobody is in voice yet.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AddressUpdateHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final InstallationRepository installationRepository;
    private final SfuLauncher sfuLauncher;

    @Override
    public WsMessageType getType() {
        return WsMessageType.ADDRESS_UPDATE;
    }

    @Override
    public void handle(JsonObject payload) {
        AddressUpdatePayload update = gson.fromJson(payload, AddressUpdatePayload.class);
        String address = update == null || update.getPublicAddress() == null ? null : update.getPublicAddress().trim();
        if (address == null || address.isEmpty()) {
            return;
        }
        Installation installation = installationRepository.findAll().stream().findFirst().orElse(null);
        if (installation == null) {
            return;
        }
        if (!address.equals(installation.getPublicAddress())) {
            log.info("The hub gives players the address {} for this server (was {})", address, installation.getPublicAddress());
            installation.setPublicAddress(address);
            installationRepository.save(installation);
        }
        sfuLauncher.refreshNodeIp();
    }
}
