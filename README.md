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

## 🚀 Deployment Instructions

### Prerequisites

- Java 17+
- Maven 3.6+
- Docker
- Git

### Steps

**1. Clone and configure**
```bash
git clone https://github.com/tecnomod-um/MEDIATA_node.git
cd MEDIATA_node
cp node-secrets.env.example node-secrets.env
# Edit node-secrets.env with your configuration
```

**2. Build**
```bash
mvn clean package
```

**3. Build Docker image**
```bash
sudo docker build -t taniwha-backend-node .
```

**4. Run**
```bash
sudo docker run -d \
  --network host \
  --env-file ./node-secrets.env \
  -v ./taniwha:/taniwha \
  -e PORT=8080 \
  -e NAME="YourNodeName" \
  -e DESC="Your MEDIATA server" \
  -e COLOR=#008000 \
  -e NODE_IP=https://yournode.mediata.dev \
  taniwha-backend-node
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

## 🧪 Development

### Run Tests
```bash
mvn test
```

### Run Locally (without Docker)
```bash
mvn clean package
java -jar target/TANIWHA_Backend_node.jar
```

---

## 📊 Container Management

```bash
# View running containers
sudo docker ps

# View logs
sudo docker logs [container-id]

# Stop container
sudo docker stop [container-id]
```

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
