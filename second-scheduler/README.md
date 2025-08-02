This folder was specifically made to make it easier
to run and deploy a second scheduler for KWOK.

It assumes that the user copies the following files
from their `user/.kwok/clusters/kwok/pki` folder into
this working directory:
- admin.crt
- admin.key
- ca.crt

One may use one of the following commands to run a container:

### Option 1 - Dockerfile
```cli
docker build -t my-scheduler .
```
```cli
docker run --rm --network host my-scheduler
```

### Option 2 - Docker Compose
```
docker-compose up -d
```

### Option 3 - sh script.
```
chmod +x run-scheduler.sh
./run-scheduler.sh &
```

### Disclaimer
This organization is likely NOT the best way to manage this.
This will be refactored into a better way once the implementation has been
confirmed to work.