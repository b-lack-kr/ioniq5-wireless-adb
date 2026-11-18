/* NEEV private ADB diagnostic, Apache-2.0. */
#ifndef NEEV_EVENTS_H
#define NEEV_EVENTS_H
#include <stdint.h>
#include <sys/types.h>

enum neev_service { NEEV_SERVICE_NONE, NEEV_SERVICE_SHELL,
                    NEEV_SERVICE_EXEC, NEEV_SERVICE_SYNC };
/* The worker opens regular, private files as descriptors 7, 8 and 9.
 * An unavailable monitor is optional and never changes ADB authorization. */
void neev_monitor_init(uid_t expected_owner);
void neev_monitor_start(void);
void neev_monitor_flush(void);
void neev_monitor_authenticated(int authenticated);
void neev_monitor_auth_rejected(void);
void neev_monitor_packet(int transmit, uint32_t command, uint32_t payload_length);
unsigned neev_monitor_service_kind(const char *name);
void neev_monitor_service_open(unsigned kind, unsigned channel);
void neev_monitor_service_close(unsigned kind, unsigned channel);
#endif
