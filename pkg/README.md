```bash
openssl req -newkey rsa:2048 -new -nodes -x509 -days 3650 -keyout key.pem -out ~/.pkl/cacerts/cert.pem -subj "/C=GB/CN=127.0.0.1" -addext "subjectAltName = 127.0.0.1"
npx http-server -S -C ~/.pkl/cacerts/cert.pem -o 
pkl project package
```
