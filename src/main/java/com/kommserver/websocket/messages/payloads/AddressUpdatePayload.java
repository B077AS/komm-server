package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** From the hub on every connect: the address players are given for this server (its public address, else its IP). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressUpdatePayload {
    private String publicAddress;
}
