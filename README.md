# MEDIATA Node Service

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?style=flat&logo=spring-boot&logoColor=white)](#)
[![CI](https://github.com/tecnomod-um/MEDIATA_node/actions/workflows/ci_testing.yml/badge.svg?branch=main)](https://github.com/tecnomod-um/MEDIATA_node/actions/workflows/ci_testing.yml)
[![Coverage](./badges/coverage.svg)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE.md)


This repository contains the **on-site portion of the backend** of the MEDIATA platform.  
For the complete platform (front end, back end, orchestration, and docs), see **[tecnomod-um/MEDIATA_project](https://github.com/tecnomod-um/MEDIATA_project)**.

## Overview

This component is deployed on-premise at each clinical site. It hosts and processes sensitive datasets locally and responds only to authenticated users holding valid Kerberos tickets.

## Features

- Secure Kerberos ticket validation
- Local dataset analysis (univariate + bivariate stats)
- Lightweight preprocessing and integration pipeline
- Dataset and DCAT metadata exposure
- Exportable mapped files and RDF resources

---

## 🚀 Quick Start: Deployment Instructions

### Prerequisites

Before deploying the MEDIATA Node, ensure you have the following installed:

- **Java 17** or higher ([Eclipse Temurin](https://adoptium.net/) recommended)
- **Maven 3.6+** for building from source
- **Docker** for containerized deployment
- **Git** for cloning the repository

### Step 1: Clone the Repository

```bash
git clone https://github.com/tecnomod-um/MEDIATA_node.git
cd MEDIATA_node
```

### Step 2: Configure Environment Variables

Create your environment configuration file:

```bash
cp node-secrets.env.example node-secrets.env
```

Edit `node-secrets.env` with your specific configuration:

```bash
# Required: Update these values
PORT=8080
NAME=YourNodeName
DESC="Your MEDIATA node description"
COLOR=#008000
NODE_IP=https://yournode.mediata.dev

# CRITICAL: Change this to a secure 32+ character secret!
JWT_SECRET=change-this-to-a-secure-random-string-at-least-32-chars
JWT_EXPIRATION=86400
```

**⚠️ Security Warning:** 
- The `JWT_SECRET` **must** be at least 32 characters for HMAC-SHA256 security
- Never commit `node-secrets.env` to version control (it's in `.gitignore`)
- Use a cryptographically secure random string for production

### Step 3: Build the Application

```bash
# Clean build (skip tests for faster build)
mvn clean package -DskipTests

# Or build with tests (recommended for production)
mvn clean package
```

This creates `target/TANIWHA_Backend_node.jar` (~79MB).

**Build verification:**
```bash
ls -lh target/TANIWHA_Backend_node.jar
# Should show: -rw-rw-r-- 1 user user 79M [date] target/TANIWHA_Backend_node.jar
```

### Step 4: Build Docker Image

```bash
sudo docker build -t taniwha-backend-node .
```

**Build verification:**
```bash
sudo docker images | grep taniwha-backend-node
# Should show: taniwha-backend-node  latest  [image-id]  [time]  [size]
```

### Step 5: Run the Docker Container

```bash
sudo docker run -d \
  --network host \
  --env-file ./node-secrets.env \
  -v ./taniwha:/taniwha \
  -e PORT=8080 \
  -e NAME="YourNodeName" \
  -e DESC="Your MEDIATA server description" \
  -e COLOR=#008000 \
  -e NODE_IP=https://yournode.mediata.dev \
  taniwha-backend-node
```

**Container verification:**
```bash
# Check container is running
sudo docker ps | grep taniwha-backend-node

# View container logs
sudo docker logs [container-id]
```

### Step 6: Verify Deployment

Test the node is running:

```bash
# Health check (if endpoint exists)
curl http://localhost:8080/actuator/health

# Or check if port is listening
netstat -tuln | grep 8080
```

---

## 📁 Directory Structure

The Docker container creates and mounts the following directories in `/taniwha`:

```
/taniwha/
├── datasets/              # Source datasets (CSV, TSV, XLSX, TTL)
├── mapped_datasets/       # Processed and harmonized datasets
├── fhir_mappings/        # FHIR mapping configurations
├── dataset_elements/     # Dataset element metadata
└── dataset_metadata/     # DCAT and dataset metadata
```

**Local volume mapping:**
- The `-v ./taniwha:/taniwha` flag mounts a local `taniwha/` directory
- Create this directory before first run: `mkdir -p taniwha`
- Data persists across container restarts

---

## 🔧 Configuration Options

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `PORT` | No | `8080` | Application port |
| `NAME` | No | `SCUBA` | Node display name |
| `DESC` | No | `This is the description for the new node` | Node description |
| `COLOR` | No | `#21c2c3` | Node color (hex) |
| `NODE_IP` | No | `http://localhost` | Public node URL |
| `JWT_SECRET` | **Yes** | - | JWT signing secret (32+ chars) |
| `JWT_EXPIRATION` | No | `86400` | Token expiration (seconds) |

### Running with Custom Configuration

```bash
# Option 1: Use environment file
sudo docker run -d --env-file ./node-secrets.env taniwha-backend-node

# Option 2: Pass individual environment variables
sudo docker run -d \
  -e PORT=9090 \
  -e JWT_SECRET="your-secure-secret-key-here" \
  taniwha-backend-node
```

---

## 🧪 Development & Testing

### Run Tests

```bash
# Run all tests
mvn test

# Run tests with coverage
mvn test jacoco:report

# View coverage report
open target/site/jacoco/index.html
```

### Run Locally (without Docker)

```bash
# Build the JAR
mvn clean package -DskipTests

# Run directly
java -jar target/TANIWHA_Backend_node.jar \
  --PORT=8080 \
  --NODE_IP=http://localhost \
  --NAME="LocalDev" \
  --DESC="Development instance" \
  --COLOR=#FF5733
```

---

## 🐛 Troubleshooting

### Build Issues

**Problem:** Maven build fails with "Java version" error
```bash
# Solution: Verify Java 17 is installed
java -version  # Should show version 17 or higher
mvn -version   # Should show Java version 17
```

**Problem:** Build fails with dependency errors
```bash
# Solution: Clean Maven cache and retry
mvn clean
rm -rf ~/.m2/repository
mvn package
```

### Docker Issues

**Problem:** "Permission denied" when running Docker commands
```bash
# Solution: Add user to docker group or use sudo
sudo docker run ...
# OR
sudo usermod -aG docker $USER  # Logout and login after this
```

**Problem:** Container exits immediately
```bash
# Solution: Check logs for errors
sudo docker logs [container-id]

# Common issues:
# - Missing JWT_SECRET in environment
# - Port already in use (change PORT variable)
# - Invalid environment variable format
```

**Problem:** Cannot connect to the application
```bash
# Check if port is correctly exposed
sudo docker ps  # Verify PORT mapping

# Check firewall rules
sudo ufw status
sudo ufw allow 8080/tcp  # If needed
```

### Volume Permission Issues

**Problem:** Cannot write to `/taniwha` directories
```bash
# Solution: Check permissions on host directory
ls -la taniwha/
chmod -R 777 taniwha/  # Or more restrictive as needed
```

---

## 📊 Monitoring

### Container Management

```bash
# View running containers
sudo docker ps

# Stop container
sudo docker stop [container-id]

# Remove container
sudo docker rm [container-id]

# View logs (live)
sudo docker logs -f [container-id]

# Execute commands in running container
sudo docker exec -it [container-id] /bin/sh
```

### System Resources

```bash
# Check container resource usage
sudo docker stats [container-id]

# View container details
sudo docker inspect [container-id]
```

---

## 🔐 Security Best Practices

1. **JWT Secret:** Use a cryptographically secure random string (32+ characters)
   ```bash
   # Generate secure secret
   openssl rand -base64 32
   ```

2. **HTTPS:** Always use HTTPS for `NODE_IP` in production
3. **Environment Files:** Never commit `node-secrets.env` to version control
4. **Firewall:** Restrict port access to authorized IPs only
5. **Updates:** Regularly update dependencies and rebuild
6. **Monitoring:** Enable application logging and monitoring

---

## 📚 Additional Resources

- **Full Platform Documentation:** [MEDIATA_project](https://github.com/tecnomod-um/MEDIATA_project)
- **API Documentation:** See `DOCUMENTATION.md`
- **Development Guidelines:** See `GUIDELINES.md`
- **Issue Tracking:** [GitHub Issues](https://github.com/tecnomod-um/MEDIATA_node/issues)

---

## 📄 License

This project is developed under the [MIT License](LICENSE.md).

---

## 🤝 Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Submit a pull request

Ensure all tests pass before submitting:
```bash
mvn clean test
```
