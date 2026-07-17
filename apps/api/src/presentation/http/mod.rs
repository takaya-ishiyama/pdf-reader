pub mod document_handler;
pub mod error;

use axum::Router;

use crate::di::AppState;

pub fn document_routes() -> Router<AppState> {
    document_handler::routes()
}
