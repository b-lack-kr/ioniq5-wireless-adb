/* NEEV private ADB diagnostic, Apache-2.0. The AOSP main transport event loop
 * serializes these callbacks. The daemon permits one host transport at a time. */
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include "events.h"
#include "sysdeps.h"
#include "adb.h"

static atransport *verified_transport;
static adisconnect verified_disconnect;

static void client_disconnected(void *opaque, atransport *transport) {
    (void)opaque;
    if (verified_transport != transport) return;
    /* Offline and final removal can both run the disconnect list. */
    remove_transport_disconnect(transport, &verified_disconnect);
    verified_transport = NULL;
    neev_monitor_authenticated(0);
}

void neev_client_authenticated(atransport *transport) {
    if (verified_transport == transport) return;
    if (verified_transport) client_disconnected(NULL, verified_transport);
    verified_transport = transport;
    verified_disconnect.func = client_disconnected;
    verified_disconnect.opaque = NULL;
    add_transport_disconnect(transport, &verified_disconnect);
    neev_monitor_authenticated(1);
}
