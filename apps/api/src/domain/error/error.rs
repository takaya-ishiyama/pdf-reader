use std::{error::Error, fmt};

#[derive(Debug)]
pub struct ApiError {
    pub code: u16,
    pub message: String,
    pub error: Option<Box<dyn Error>>,
}

#[derive(thiserror::Error, Debug, PartialEq)]
pub enum DomainError {}
