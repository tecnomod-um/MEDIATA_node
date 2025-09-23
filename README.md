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

This project is developed under the [MIT License](LICENSE.md).
