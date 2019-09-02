FROM nvidia/cuda:9.0-base-centos7

RUN yum update
RUN yum repolist 
RUN yum install -y make cmake curl git vim

RUN rpm --import https://mirror.go-repo.io/centos/RPM-GPG-KEY-GO-REPO
RUN curl -s https://mirror.go-repo.io/centos/go-repo.repo | tee /etc/yum.repos.d/go-repo.repo
RUN yum repolist
RUN yum install -y golang 

WORKDIR /root
ENV WORKSPACE /root/beats_dev
ENV GOPATH $WORKSPACE

RUN git clone https://github.com/elastic/beats ${GOPATH}/src/github.com/elastic/beats --branch 7.3
RUN mkdir -p $WORKSPACE/src/github.com/ebay
WORKDIR $WORKSPACE/src/github.com/ebay
RUN git clone https://github.com/eBay/nvidiagpubeat --branch withBeats7.3
WORKDIR $WORKSPACE/src/github.com/ebay/nvidiagpubeat
RUN make setup 
RUN make

# -------
COPY nvidiagpubeat.yml $WORKSPACE/src/github.com/ebay/nvidiagpubeat/nvidiagpubeat.yml
ENV RUNPATH $WORKSPACE/src/github.com/ebay/nvidiagpubeat
COPY gpubeatrun.sh /gpubeatrun.sh
RUN chmod +x /gpubeatrun.sh

ENTRYPOINT ["/gpubeatrun.sh"]



