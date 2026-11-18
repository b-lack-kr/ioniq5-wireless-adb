/* NEEV private ADB diagnostic, Apache-2.0. */
void neev_prepare(const char *iface, const char *key_file);
void neev_accept_clients(void);
int neev_verify_key(const char *key_file);
struct atransport;
void neev_client_authenticated(struct atransport *transport);
