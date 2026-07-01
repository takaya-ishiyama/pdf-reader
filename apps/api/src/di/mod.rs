use std::sync::Arc;

use crate::{
    application::document::usecase::{
        GetDocumentUseCase, ListDocumentsUseCase, UpdateReadingProgressUseCase,
    },
    infrastructure::{
        database::DbPool,
        document_repository::SqlxDocumentRepository,
        gcs_signed_url_gateway::{ConfiguredSignedUrlGateway, SignedUrlError},
    },
};

pub type ListDocumentsUseCaseImpl = ListDocumentsUseCase<SqlxDocumentRepository>;
pub type GetDocumentUseCaseImpl =
    GetDocumentUseCase<SqlxDocumentRepository, ConfiguredSignedUrlGateway>;
pub type UpdateReadingProgressUseCaseImpl = UpdateReadingProgressUseCase<SqlxDocumentRepository>;

#[derive(Clone)]
pub struct AppState {
    pub repositories: Repositories,
    pub gateways: Gateways,
    pub usecases: UseCases,
}

#[derive(Clone)]
pub struct Repositories {
    pub document_repository: Arc<SqlxDocumentRepository>,
}

#[derive(Clone)]
pub struct Gateways {
    pub signed_url_gateway: Arc<ConfiguredSignedUrlGateway>,
}

#[derive(Clone)]
pub struct UseCases {
    pub list_documents: Arc<ListDocumentsUseCaseImpl>,
    pub get_document: Arc<GetDocumentUseCaseImpl>,
    pub update_reading_progress: Arc<UpdateReadingProgressUseCaseImpl>,
}

impl AppState {
    pub fn new(db_pool: DbPool) -> Result<Self, SignedUrlError> {
        let document_repository = Arc::new(SqlxDocumentRepository::new(db_pool));
        let signed_url_gateway = Arc::new(ConfiguredSignedUrlGateway::from_env()?);
        Ok(Self::from_parts(document_repository, signed_url_gateway))
    }

    pub fn new_with_signed_url_gateway(
        db_pool: DbPool,
        signed_url_gateway: ConfiguredSignedUrlGateway,
    ) -> Self {
        let document_repository = Arc::new(SqlxDocumentRepository::new(db_pool));
        Self::from_parts(document_repository, Arc::new(signed_url_gateway))
    }

    fn from_parts(
        document_repository: Arc<SqlxDocumentRepository>,
        signed_url_gateway: Arc<ConfiguredSignedUrlGateway>,
    ) -> Self {
        let repositories = Repositories {
            document_repository: document_repository.clone(),
        };
        let gateways = Gateways {
            signed_url_gateway: signed_url_gateway.clone(),
        };
        let usecases = UseCases {
            list_documents: Arc::new(ListDocumentsUseCase::new(document_repository.clone())),
            get_document: Arc::new(GetDocumentUseCase::new(
                document_repository.clone(),
                signed_url_gateway,
            )),
            update_reading_progress: Arc::new(UpdateReadingProgressUseCase::new(
                document_repository,
            )),
        };

        Self {
            repositories,
            gateways,
            usecases,
        }
    }
}
