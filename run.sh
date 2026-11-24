
#!/bin/sh
# Starts MediQueue Web. Open http://localhost:8080 in your browser, and press Ctrl+C here to stop it.
cd "$(dirname "$0")"
exec java -jar MediQueueWeb.jar "$@"
