#!/bin/bash

sudo systemctl stop stbl
sudo cp target/stbl-service-0.2.0-SNAPSHOT.jar /var/stbl-service/
sudo systemctl start stbl
